package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What is kept per Discord user: the standee pictures, the secret overlay address, how the overlay looks, and whether the
 * bot should join automatically.
 *
 * <p>A person can have one picture for each server and one shared picture (scope 0). Which one is shown: the one for the
 * server the voice channel is in, else the shared one, else (in the overlay) the Discord avatar.
 *
 * <p>Pictures are files named by a random key (so they cannot be guessed from a Discord id); the rest is one small JSON
 * file, rewritten atomically. Reads are lock free (the overlay asks for them all the time); changes are synchronized.
 */
final class StandeeStore {

    /** The scope of the picture that is shown in every server that has no picture of its own. */
    static final long SHARED = 0;
    /** How many servers one person can have their own picture for. */
    static final int MAX_SERVER_PICTURES = 20;

    static final Pattern IMAGE_KEY = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param imageKey     file name stem of the shared picture, or null
     * @param token        secret of the overlay address, or null
     * @param settings     how the overlay looks, or null for the defaults
     * @param guildImages  picture keys by server id
     */
    record Entry(String imageKey, String token, boolean auto, OverlaySettings settings, Map<Long, String> guildImages) {
        static final Entry NONE = new Entry(null, null, true, null, Map.of());

        boolean isEmpty() {
            return imageKey == null && token == null && guildImages.isEmpty();
        }

        Entry withImageKey(String key) {
            return new Entry(key, token, auto, settings, guildImages);
        }

        Entry withToken(String value) {
            return new Entry(imageKey, value, auto, settings, guildImages);
        }

        Entry withAuto(boolean value) {
            return new Entry(imageKey, token, value, settings, guildImages);
        }

        Entry withSettings(OverlaySettings value) {
            return new Entry(imageKey, token, auto, value, guildImages);
        }

        Entry withGuildImages(Map<Long, String> value) {
            return new Entry(imageKey, token, auto, settings, Map.copyOf(value));
        }

        String keyFor(long scope) {
            return scope == SHARED ? imageKey : guildImages.get(scope);
        }
    }

    private final Path imageDir;
    private final Path file;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Long> byToken = new ConcurrentHashMap<>();

    StandeeStore(Path dir) throws IOException {
        this.imageDir = dir.resolve("standees");
        this.file = dir.resolve("users.json");
        Files.createDirectories(imageDir);
        load();
    }

    // ---- reads -----------------------------------------------------------------------------------------

    /** The picture of exactly this scope: a server's own, or (scope 0) the shared one. */
    Optional<String> imageKeyOf(long userId, long scope) {
        Entry e = entries.get(userId);
        return e == null ? Optional.empty() : Optional.ofNullable(e.keyFor(scope));
    }

    /** The shared picture. */
    Optional<String> imageKey(long userId) {
        return imageKeyOf(userId, SHARED);
    }

    /** The picture to show for this person in this server: its own if there is one, else the shared one. */
    Optional<String> imageKey(long guildId, long userId) {
        Optional<String> own = imageKeyOf(userId, guildId);
        return own.isPresent() ? own : imageKeyOf(userId, SHARED);
    }

    /** Any picture of this person, for a preview when no server is in play: the shared one first. */
    Optional<String> anyImageKey(long userId) {
        Entry e = entries.get(userId);
        if (e == null) {
            return Optional.empty();
        }
        if (e.imageKey() != null) {
            return Optional.of(e.imageKey());
        }
        return e.guildImages().values().stream().findFirst();
    }

    Optional<String> token(long userId) {
        Entry e = entries.get(userId);
        return e == null ? Optional.empty() : Optional.ofNullable(e.token());
    }

    Optional<Long> userForToken(String token) {
        return Optional.ofNullable(byToken.get(token));
    }

    /** A user who has an overlay address and has not switched the automatic joining off. */
    boolean isAutoStreamer(long userId) {
        Entry e = entries.get(userId);
        return e != null && e.token() != null && e.auto();
    }

    boolean isAuto(long userId) {
        Entry e = entries.get(userId);
        return e == null || e.auto();
    }

    OverlaySettings settings(long userId) {
        Entry e = entries.get(userId);
        return e == null || e.settings() == null ? OverlaySettings.DEFAULT : e.settings();
    }

    Optional<byte[]> readImage(String key) {
        if (key == null || !IMAGE_KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(imageDir.resolve(key + ".png")));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the picture " + key, e);
        }
    }

    // ---- changes ---------------------------------------------------------------------------------------

    /** Sets the shared picture. */
    synchronized void setImage(long userId, byte[] png) throws IOException {
        setImage(userId, SHARED, png);
    }

    /**
     * @param scope a server id, or {@link #SHARED}
     * @return false if the person already has {@link #MAX_SERVER_PICTURES} server pictures (nothing was changed)
     */
    synchronized boolean setImage(long userId, long scope, byte[] png) throws IOException {
        Entry old = entries.getOrDefault(userId, Entry.NONE);
        if (scope != SHARED && !old.guildImages().containsKey(scope) && old.guildImages().size() >= MAX_SERVER_PICTURES) {
            return false;
        }
        String key = randomHex(16);
        Files.write(imageDir.resolve(key + ".png"), png);
        String replaced = old.keyFor(scope);
        entries.put(userId, withPicture(old, scope, key));
        deleteImageFile(replaced);
        save();
        return true;
    }

    /** Removes the shared picture. */
    synchronized boolean removeImage(long userId) throws IOException {
        return removeImage(userId, SHARED);
    }

    /** @return whether there was a picture to remove */
    synchronized boolean removeImage(long userId, long scope) throws IOException {
        Entry old = entries.get(userId);
        if (old == null || old.keyFor(scope) == null) {
            return false;
        }
        Entry now = withPicture(old, scope, null);
        if (now.isEmpty()) {
            entries.remove(userId);
        } else {
            entries.put(userId, now);
        }
        deleteImageFile(old.keyFor(scope));
        save();
        return true;
    }

    private static Entry withPicture(Entry e, long scope, String key) {
        if (scope == SHARED) {
            return e.withImageKey(key);
        }
        Map<Long, String> copy = new HashMap<>(e.guildImages());
        if (key == null) {
            copy.remove(scope);
        } else {
            copy.put(scope, key);
        }
        return e.withGuildImages(copy);
    }

    synchronized String ensureToken(long userId) throws IOException {
        Entry old = entries.getOrDefault(userId, Entry.NONE);
        if (old.token() != null) {
            return old.token();
        }
        return issueToken(userId, old);
    }

    /** Issues a new address and makes the old one stop working (for when it has leaked). */
    synchronized String resetToken(long userId) throws IOException {
        Entry old = entries.getOrDefault(userId, Entry.NONE);
        if (old.token() != null) {
            byToken.remove(old.token());
        }
        return issueToken(userId, old);
    }

    synchronized void setAuto(long userId, boolean auto) throws IOException {
        entries.put(userId, entries.getOrDefault(userId, Entry.NONE).withAuto(auto));
        save();
    }

    synchronized void setSettings(long userId, OverlaySettings settings) throws IOException {
        entries.put(userId, entries.getOrDefault(userId, Entry.NONE).withSettings(settings));
        save();
    }

    private String issueToken(long userId, Entry old) throws IOException {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        entries.put(userId, old.withToken(token));
        byToken.put(token, userId);
        save();
        return token;
    }

    private void deleteImageFile(String key) {
        if (key == null) {
            return;
        }
        try {
            Files.deleteIfExists(imageDir.resolve(key + ".png"));
        } catch (IOException ignored) {
            // an orphaned file is harmless
        }
    }

    // ---- persistence -----------------------------------------------------------------------------------

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        DataObject root = DataObject.fromJson(Files.readString(file));
        DataArray users = root.getArray("users");
        for (int i = 0; i < users.length(); i++) {
            DataObject u = users.getObject(i);
            long id = Long.parseLong(u.getString("id"));
            OverlaySettings settings = u.hasKey("settings") && !u.isNull("settings")
                    ? OverlaySettings.fromJson(u.getObject("settings")) : null;
            Map<Long, String> guildImages = new HashMap<>();
            if (u.hasKey("guildImages") && !u.isNull("guildImages")) {
                DataObject g = u.getObject("guildImages");
                for (String guildId : g.keys()) {
                    guildImages.put(Long.parseLong(guildId), g.getString(guildId));
                }
            }
            Entry e = new Entry(u.getString("imageKey", null), u.getString("token", null), u.getBoolean("auto", true), settings,
                    Map.copyOf(guildImages));
            entries.put(id, e);
            if (e.token() != null) {
                byToken.put(e.token(), id);
            }
        }
    }

    private void save() throws IOException {
        DataArray users = DataArray.empty();
        entries.forEach((id, e) -> {
            DataObject o = DataObject.empty().put("id", Long.toString(id)).put("auto", e.auto());
            if (e.imageKey() != null) {
                o.put("imageKey", e.imageKey());
            }
            if (e.token() != null) {
                o.put("token", e.token());
            }
            if (e.settings() != null) {
                o.put("settings", e.settings().toJson());
            }
            if (!e.guildImages().isEmpty()) {
                DataObject g = DataObject.empty();
                e.guildImages().forEach((guildId, key) -> g.put(Long.toString(guildId), key));
                o.put("guildImages", g);
            }
            users.add(o);
        });
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, DataObject.empty().put("users", users).toString());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
