package com.yuaworks.voicestandee;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings, all from environment variables so that no secret ever sits in a file that is committed.
 *
 * <ul>
 *   <li>VOICE_STANDEE_TOKEN (required): the bot token.</li>
 *   <li>VOICE_STANDEE_EXTRA_TOKENS: the tokens of more bots (separated by commas or spaces), for a server that has streamers in
 *       several voice channels at the same time (two collaboration rooms). The main bot (VOICE_STANDEE_TOKEN) takes the commands;
 *       the others only listen in voice channels, one channel each.</li>
 *   <li>VOICE_STANDEE_OWNER: the Discord user id of the person who looks after the bot. Reports about pictures go to them
 *       (as a private message) and only they can use the moderation command. Without it, reporting and moderation are off.</li>
 *   <li>VOICE_STANDEE_GUILD: a test server's id; makes the slash commands appear at once there (instead of globally).</li>
 *   <li>VOICE_STANDEE_BIND (default 127.0.0.1) and VOICE_STANDEE_PORT (default 8780): where the overlay web server listens.</li>
 *   <li>VOICE_STANDEE_BASE_URL (default http://127.0.0.1:PORT): the address people put into OBS; set it to the public one once published.</li>
 *   <li>VOICE_STANDEE_DATA (default ./data): where the registered pictures and the user list are kept.</li>
 * </ul>
 */
record Config(String discordToken, long ownerId, String devGuildId, String bind, int port, String baseUrl, Path dataDir,
              List<String> extraTokens) {

    Config {
        extraTokens = List.copyOf(extraTokens);
    }

    static Config fromEnv() {
        String token = env("VOICE_STANDEE_TOKEN", "");
        if (token.isBlank()) {
            throw new IllegalStateException("Set the environment variable VOICE_STANDEE_TOKEN first.");
        }
        long owner = 0;
        String ownerText = env("VOICE_STANDEE_OWNER", "");
        if (!ownerText.isBlank()) {
            try {
                owner = Long.parseLong(ownerText);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("VOICE_STANDEE_OWNER must be a Discord user id (digits only).");
            }
        }
        int port = Integer.parseInt(env("VOICE_STANDEE_PORT", "8780"));
        String base = env("VOICE_STANDEE_BASE_URL", "http://127.0.0.1:" + port);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new Config(token, owner, env("VOICE_STANDEE_GUILD", ""), env("VOICE_STANDEE_BIND", "127.0.0.1"), port, base,
                Path.of(env("VOICE_STANDEE_DATA", "data")), parseTokens(env("VOICE_STANDEE_EXTRA_TOKENS", ""), token));
    }

    /** The extra tokens: separated by commas, semicolons or spaces; repeats, and the main token, are left out. */
    static List<String> parseTokens(String raw, String mainToken) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw == null ? new String[0] : raw.split("[,;\s]+")) {
            String token = part.trim();
            if (!token.isEmpty() && !token.equals(mainToken)) {
                out.add(token);
            }
        }
        return new ArrayList<>(out);
    }

    boolean baseUrlIsLocal() {
        return baseUrl.contains("://127.0.0.1") || baseUrl.contains("://localhost");
    }

    boolean hasOwner() {
        return ownerId != 0;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
