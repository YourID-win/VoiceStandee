package com.yuaworks.voicestandee;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayServerTest {

    @TempDir
    Path dir;

    private StandeeStore store;
    private OverlayServer server;
    private String base;
    private String token;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.EMPTY);
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        store = new StandeeStore(dir);
        token = store.ensureToken(42);
        server = new OverlayServer("127.0.0.1", 0, store, streamer -> streamer == 42 ? current.get() : Snapshot.EMPTY);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theOverlayPageIsServedOnlyForAKnownSecret() throws Exception {
        HttpResponse<String> ok = get("/o/" + token);
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().contains("/assets/overlay.js"));
        assertTrue(ok.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        assertEquals("no-store", ok.headers().firstValue("Cache-Control").orElse(""));

        assertEquals(404, get("/o/" + "x".repeat(32)).statusCode());
        assertEquals(404, get("/o/short").statusCode());
        assertEquals(404, get("/o/" + token + "/other").statusCode());
        assertEquals(404, get("/").statusCode());
        assertEquals(404, get("/users.json").statusCode());
    }

    @Test
    void picturesAreServedByTheirRandomKeyOnly() throws Exception {
        store.setImage(42, new byte[] {10, 20, 30});
        String key = store.imageKey(42).orElseThrow();
        HttpResponse<byte[]> image = http.send(HttpRequest.newBuilder(URI.create(base + "/img/" + key + ".png")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, image.statusCode());
        assertArrayEquals(new byte[] {10, 20, 30}, image.body());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElse(""));

        assertEquals(404, get("/img/42.png").statusCode());
        assertEquals(404, get("/img/" + "0".repeat(32) + ".png").statusCode());
        assertEquals(404, get("/img/..%2Fusers.json").statusCode());
    }

    @Test
    void theFeedStartsWithTheCurrentStateAndThenFollowsChanges() throws Exception {
        current.set(new Snapshot(true, List.of(new Snapshot.Person("42", "ゆあ", null, "https://cdn.discordapp.com/a.png", false))));
        HttpResponse<java.io.InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/o/" + token + "/events")).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

        try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String first = nextData(in);
            assertTrue(first.contains("\"name\":\"ゆあ\""), first);
            assertTrue(first.contains("\"speaking\":false"), first);

            current.set(new Snapshot(true, List.of(new Snapshot.Person("42", "ゆあ", null, null, true))));
            server.publish();
            String second = nextData(in);
            assertTrue(second.contains("\"speaking\":true"), second);
        }
    }

    private HttpResponse<String> post(String path, String type, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (type != null) {
            request.header("Content-Type", type);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thePageFilesAndTheSettingsPageAreServed() throws Exception {
        for (String name : List.of("overlay.js", "overlay.css", "settings.js", "settings.css")) {
            assertEquals(200, get("/assets/" + name).statusCode(), name);
        }
        assertEquals(404, get("/assets/nothing.js").statusCode());
        assertEquals(404, get("/assets/../users.json").statusCode());
        HttpResponse<String> page = get("/o/" + token + "/settings");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("settings.js"));
        assertEquals(404, get("/o/" + "z".repeat(32) + "/settings").statusCode());
    }

    @Test
    void savedSettingsAreKeptClampedAndPushedToTheFeed() throws Exception {
        HttpResponse<java.io.InputStream> feed = http.send(
                HttpRequest.newBuilder(URI.create(base + "/o/" + token + "/events")).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(feed.body(), StandardCharsets.UTF_8))) {
            assertTrue(nextData(in).contains("\"hopSpeed\":0.32"), "starts with the defaults");

            HttpResponse<String> saved = post("/o/" + token + "/settings", "application/json",
                    "{\"hopSpeed\":0.2,\"hopHeight\":1.5,\"h\":9999,\"names\":true}");
            assertEquals(200, saved.statusCode());
            assertTrue(saved.body().contains("\"h\":1500"), saved.body());

            String pushed = nextData(in);
            assertTrue(pushed.contains("\"hopSpeed\":0.2"), pushed);
            assertTrue(pushed.contains("\"names\":true"), pushed);
        }
        assertEquals(0.2, store.settings(42).hopSpeed());
        assertEquals(1500, store.settings(42).h());
    }

    @Test
    void badSettingsRequestsAreRefused() throws Exception {
        String url = "/o/" + token + "/settings";
        assertEquals(415, post(url, null, "{}").statusCode());
        assertEquals(415, post(url, "text/plain", "{}").statusCode());
        assertEquals(400, post(url, "application/json", "not json").statusCode());
        assertEquals(413, post(url, "application/json", "{\"x\":\"" + "a".repeat(5000) + "\"}").statusCode());
        assertEquals(404, post("/o/" + "q".repeat(32) + "/settings", "application/json", "{}").statusCode());
        assertEquals(405, post("/o/" + token, "application/json", "{}").statusCode());
        assertEquals(OverlaySettings.DEFAULT, store.settings(42), "nothing was saved");
    }

    @Test
    void aFeedWithTheWrongSecretIsRefused() throws Exception {
        assertEquals(404, get("/o/" + "y".repeat(32) + "/events").statusCode());
    }

    @Test
    void oneSecretCannotOpenUnlimitedFeeds() throws Exception {
        for (int i = 0; i < 8; i++) {
            http.sendAsync(HttpRequest.newBuilder(URI.create(base + "/o/" + token + "/events")).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        }
        Thread.sleep(500);
        assertEquals(429, get("/o/" + token + "/events").statusCode());
    }

    private static String nextData(BufferedReader in) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("data: ")) {
                return line.substring(6);
            }
        }
        throw new AssertionError("the feed ended");
    }
}
