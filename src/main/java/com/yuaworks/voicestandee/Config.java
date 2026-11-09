package com.yuaworks.voicestandee;

import java.nio.file.Path;

/**
 * Settings, all from environment variables so that no secret ever sits in a file that is committed.
 *
 * <ul>
 *   <li>VOICE_STANDEE_TOKEN (required): the bot token.</li>
 *   <li>VOICE_STANDEE_GUILD: a test server's id; makes the slash commands appear at once there (instead of globally).</li>
 *   <li>VOICE_STANDEE_BIND (default 127.0.0.1) and VOICE_STANDEE_PORT (default 8780): where the overlay web server listens.</li>
 *   <li>VOICE_STANDEE_BASE_URL (default http://127.0.0.1:PORT): the address people put into OBS; set it to the public one once published.</li>
 *   <li>VOICE_STANDEE_DATA (default ./data): where the registered pictures and the user list are kept.</li>
 * </ul>
 */
record Config(String discordToken, String devGuildId, String bind, int port, String baseUrl, Path dataDir) {

    static Config fromEnv() {
        String token = env("VOICE_STANDEE_TOKEN", "");
        if (token.isBlank()) {
            throw new IllegalStateException("Set the environment variable VOICE_STANDEE_TOKEN first.");
        }
        int port = Integer.parseInt(env("VOICE_STANDEE_PORT", "8780"));
        String base = env("VOICE_STANDEE_BASE_URL", "http://127.0.0.1:" + port);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new Config(token, env("VOICE_STANDEE_GUILD", ""), env("VOICE_STANDEE_BIND", "127.0.0.1"), port, base,
                Path.of(env("VOICE_STANDEE_DATA", "data")));
    }

    boolean baseUrlIsLocal() {
        return baseUrl.contains("://127.0.0.1") || baseUrl.contains("://localhost");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
