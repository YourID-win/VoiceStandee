package com.yuaworks.voicestandee;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs only the overlay page with made-up people who take turns speaking, so that the look and the OBS settings can be tried
 * without Discord. {@code .\gradlew demo}, then open the address it prints (in a browser, or as an OBS browser source).
 */
public final class OverlayDemo {

    private OverlayDemo() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8790;
        Path dir = Files.createTempDirectory("voice-standee-demo");
        StandeeStore store = new StandeeStore(dir);
        long[] ids = {101, 102, 103};
        store.setImage(ids[0], figure(new Color(0x4F8FE8), new Color(0xF2C9A0), 300, 520, true));
        store.setImage(ids[1], figure(new Color(0xE8678A), new Color(0xF5D5B5), 360, 480, false));
        // the third one has no standee picture: the overlay falls back to the Discord avatar
        String avatar = "data:image/svg+xml;utf8," + URLEncoder.encode(
                "<svg xmlns='http://www.w3.org/2000/svg' width='256' height='256'><rect width='256' height='256' fill='#5865f2'/>"
                        + "<text x='128' y='168' font-size='120' text-anchor='middle' fill='white' font-family='sans-serif'>G</text></svg>",
                StandardCharsets.UTF_8).replace("+", "%20");

        String[] names = {"ゆあ", "Sakura", "Guest"};
        boolean[] talking = new boolean[3];
        Random random = new Random();
        OverlayServer[] holder = new OverlayServer[1];
        OverlayServer server = new OverlayServer("127.0.0.1", port, store, streamer -> {
            List<Snapshot.Person> people = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String img = store.imageKey(ids[i]).map(k -> "/img/" + k + ".png").orElse(null);
                people.add(new Snapshot.Person(Long.toString(ids[i]), names[i], img, avatar, talking[i]));
            }
            return new Snapshot(true, people);
        });
        holder[0] = server;
        String token = store.ensureToken(ids[0]);
        server.start();
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "demo-speakers");
            t.setDaemon(false);
            return t;
        }).scheduleWithFixedDelay(() -> {
            int who = random.nextInt(3);
            talking[who] = !talking[who];
            holder[0].publish();
        }, 600, 700, TimeUnit.MILLISECONDS);
        System.out.println("Demo overlay: http://127.0.0.1:" + server.port() + "/o/" + token + "?names=1");
        System.out.println("Options: ?h=400  &gap=24  &names=1  &align=left|center|right  &idle=0.6  &anim=hop|pulse|none   (Ctrl+C to stop)");
    }

    /** A plain character: head, body and arms, drawn with a transparent margin so that the crop step has something to do. */
    private static byte[] figure(Color cloth, Color skin, int w, int h, boolean hair) throws Exception {
        BufferedImage img = new BufferedImage(w + 80, h + 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cx = img.getWidth() / 2;
        int top = 30;
        g.setColor(cloth);
        g.fillRoundRect(cx - w / 2 + 20, top + h / 3, w - 40, h * 2 / 3, 90, 90);
        g.setColor(cloth.darker());
        g.fillRoundRect(cx - w / 2, top + h / 3 + 40, 70, h / 2, 60, 60);
        g.fillRoundRect(cx + w / 2 - 70, top + h / 3 + 40, 70, h / 2, 60, 60);
        g.setColor(skin);
        int head = Math.min(w, h) * 2 / 3;
        g.fillOval(cx - head / 2, top, head, head);
        if (hair) {
            g.setColor(new Color(0x3B2A20));
            g.fillArc(cx - head / 2 - 6, top - 10, head + 12, head, 0, 180);
        } else {
            g.setColor(new Color(0xF7E08A));
            g.fillArc(cx - head / 2 - 10, top - 14, head + 20, head + 10, 0, 180);
        }
        g.setColor(new Color(0x222222));
        g.fillOval(cx - head / 4 - 8, top + head / 2, 16, 22);
        g.fillOval(cx + head / 4 - 8, top + head / 2, 16, 22);
        g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(cx - 22, top + head / 2 + 30, 44, 26, 200, 140);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        }
    }
}
