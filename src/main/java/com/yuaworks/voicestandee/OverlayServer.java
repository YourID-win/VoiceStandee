package com.yuaworks.voicestandee;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web side: the page OBS shows (a browser source), the live feed it listens to, the settings page, and the pictures.
 *
 * <ul>
 *   <li>{@code GET /o/<secret>} the overlay page. The secret in the address is what ties it to one streamer.</li>
 *   <li>{@code GET /o/<secret>/events} a server-sent-events feed of that streamer's {@link Snapshot}.</li>
 *   <li>{@code GET /o/<secret>/settings} the page where the look is adjusted; {@code POST} to the same address saves it.</li>
 *   <li>{@code GET /img/<key>.png} a registered picture (the key is random).</li>
 *   <li>{@code GET /assets/<name>} the page's own script and style files.</li>
 * </ul>
 *
 * Everything else is a plain 404, and a wrong secret looks exactly like a missing page.
 */
final class OverlayServer {

    private static final Logger LOG = LoggerFactory.getLogger(OverlayServer.class);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,64}");
    private static final Pattern IMAGE = Pattern.compile("([0-9a-f]{32})\\.png");
    private static final List<String> ASSETS = List.of("overlay.js", "overlay.css", "settings.js", "settings.css");
    private static final int MAX_CLIENTS_PER_TOKEN = 8;
    private static final int MAX_CLIENTS = 200;
    private static final int MAX_SETTINGS_BYTES = 4096;
    private static final String CSP = "default-src 'none'; img-src 'self' https://cdn.discordapp.com data:; "
            + "style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'";

    private static final class Client {
        final long streamer;
        final String token;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(32);
        String last;

        Client(long streamer, String token) {
            this.streamer = streamer;
            this.token = token;
        }
    }

    private final StandeeStore store;
    private final LongFunction<Snapshot> snapshots;
    private final HttpServer http;
    private final byte[] overlayPage;
    private final byte[] settingsPage;
    private final Map<String, byte[]> assets = new HashMap<>();
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    OverlayServer(String bind, int port, StandeeStore store, LongFunction<Snapshot> snapshots) throws IOException {
        this.store = store;
        this.snapshots = snapshots;
        this.overlayPage = resource("/overlay.html");
        this.settingsPage = resource("/settings.html");
        for (String name : ASSETS) {
            assets.put(name, resource("/assets/" + name));
        }
        this.http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        this.http.createContext("/", this::handle);
        this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = OverlayServer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " is missing from the program");
            }
            return in.readAllBytes();
        }
    }

    void start() {
        http.start();
    }

    void stop() {
        http.stop(0);
    }

    int port() {
        return http.getAddress().getPort();
    }

    /** Call whenever something an overlay shows may have changed; each connected overlay gets what is new for it. */
    synchronized void publish() {
        Map<Long, String> cache = new HashMap<>();
        for (Client c : clients) {
            String json = cache.computeIfAbsent(c.streamer, this::snapshotJson);
            if (!json.equals(c.last)) {
                c.last = json;
                if (!c.queue.offer(json)) {
                    c.queue.clear();
                    c.queue.offer(json);
                }
            }
        }
    }

    private String snapshotJson(long streamer) {
        Snapshot snapshot;
        try {
            snapshot = snapshots.apply(streamer);
        } catch (RuntimeException e) {
            LOG.warn("Could not build the overlay state", e);
            snapshot = Snapshot.EMPTY;
        }
        String selfImg = store.anyImageKey(streamer).map(k -> "/img/" + k + ".png").orElse(null);
        return snapshot.toJson(store.settings(streamer), selfImg);
    }

    // ---- routing ---------------------------------------------------------------------------------------

    private void handle(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (!"GET".equals(method) && !("POST".equals(method) && path.startsWith("/o/"))) {
                reply(ex, 405, "text/plain; charset=utf-8", new byte[0], false);
                return;
            }
            if (path.startsWith("/o/")) {
                overlay(ex, method, path.substring(3));
            } else if (path.startsWith("/img/")) {
                image(ex, path.substring(5));
            } else if (path.startsWith("/assets/")) {
                asset(ex, path.substring(8));
            } else {
                notFound(ex);
            }
        } catch (IOException e) {
            // the other side went away
        } catch (RuntimeException e) {
            LOG.warn("Request failed", e);
        } finally {
            ex.close();
        }
    }

    private void overlay(HttpExchange ex, String method, String rest) throws IOException {
        int slash = rest.indexOf('/');
        String token = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);
        if (!TOKEN.matcher(token).matches()) {
            notFound(ex);
            return;
        }
        Optional<Long> streamer = store.userForToken(token);
        if (streamer.isEmpty()) {
            notFound(ex);
            return;
        }
        boolean post = "POST".equals(method);
        if (tail.equals("/settings") && post) {
            saveSettings(ex, streamer.get());
        } else if (post) {
            reply(ex, 405, "text/plain; charset=utf-8", new byte[0], false);
        } else if (tail.isEmpty() || tail.equals("/")) {
            reply(ex, 200, "text/html; charset=utf-8", overlayPage, true);
        } else if (tail.equals("/settings") || tail.equals("/settings/")) {
            reply(ex, 200, "text/html; charset=utf-8", settingsPage, true);
        } else if (tail.equals("/events")) {
            events(ex, streamer.get(), token);
        } else {
            notFound(ex);
        }
    }

    private void saveSettings(HttpExchange ex, long streamer) throws IOException {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) {
            // a web page on another site cannot send this type without being asked first, which is refused
            reply(ex, 415, "text/plain; charset=utf-8", new byte[0], false);
            return;
        }
        byte[] body = ex.getRequestBody().readNBytes(MAX_SETTINGS_BYTES + 1);
        if (body.length > MAX_SETTINGS_BYTES) {
            reply(ex, 413, "text/plain; charset=utf-8", new byte[0], false);
            return;
        }
        OverlaySettings settings;
        try {
            settings = OverlaySettings.fromJson(DataObject.fromJson(new String(body, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            reply(ex, 400, "text/plain; charset=utf-8", new byte[0], false);
            return;
        }
        store.setSettings(streamer, settings);
        publish();
        reply(ex, 200, "application/json; charset=utf-8", settings.toJson().toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private void asset(HttpExchange ex, String name) throws IOException {
        byte[] data = assets.get(name);
        if (data == null) {
            notFound(ex);
            return;
        }
        String type = name.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8";
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        reply(ex, 200, type, data, false);
    }

    private void image(HttpExchange ex, String name) throws IOException {
        Matcher m = IMAGE.matcher(name);
        Optional<byte[]> data = m.matches() ? store.readImage(m.group(1)) : Optional.empty();
        if (data.isEmpty()) {
            notFound(ex);
            return;
        }
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        reply(ex, 200, "image/png", data.get(), false);
    }

    private void events(HttpExchange ex, long streamer, String token) throws IOException {
        Client client = new Client(streamer, token);
        synchronized (this) {
            long same = clients.stream().filter(c -> c.token.equals(token)).count();
            if (clients.size() >= MAX_CLIENTS || same >= MAX_CLIENTS_PER_TOKEN) {
                reply(ex, 429, "text/plain; charset=utf-8", new byte[0], false);
                return;
            }
            client.last = snapshotJson(streamer);
            client.queue.add(client.last);
            clients.add(client);
        }
        try {
            var headers = ex.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("X-Accel-Buffering", "no");
            headers.set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String message = client.queue.poll(15, TimeUnit.SECONDS);
                String frame = message == null ? ": keep-alive\n\n" : "data: " + message + "\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(client);
        }
    }

    private static void notFound(HttpExchange ex) throws IOException {
        reply(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8), false);
    }

    private static void reply(HttpExchange ex, int status, String type, byte[] body, boolean noStore) throws IOException {
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        if (type.startsWith("text/html")) {
            headers.set("Content-Security-Policy", CSP);
        }
        if (noStore) {
            headers.set("Cache-Control", "no-store");
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
