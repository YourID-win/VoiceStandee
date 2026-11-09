package com.yuaworks.voicestandee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandeeStoreTest {

    @TempDir
    Path dir;

    @Test
    void aPictureCanBeSetReplacedAndRemoved() throws Exception {
        StandeeStore store = new StandeeStore(dir);
        assertTrue(store.imageKey(1).isEmpty());

        store.setImage(1, new byte[] {1, 2, 3});
        String first = store.imageKey(1).orElseThrow();
        assertArrayEquals(new byte[] {1, 2, 3}, store.readImage(first).orElseThrow());

        store.setImage(1, new byte[] {4, 5});
        String second = store.imageKey(1).orElseThrow();
        assertNotEquals(first, second);
        assertTrue(store.readImage(first).isEmpty(), "the replaced file is deleted");
        assertArrayEquals(new byte[] {4, 5}, store.readImage(second).orElseThrow());

        assertTrue(store.removeImage(1));
        assertTrue(store.imageKey(1).isEmpty());
        assertTrue(store.readImage(second).isEmpty());
        assertFalse(store.removeImage(1));
    }

    @Test
    void aServersOwnPictureWinsOverTheSharedOne() throws Exception {
        StandeeStore store = new StandeeStore(dir);
        long user = 3;
        long serverA = 1001;
        long serverB = 1002;
        assertTrue(store.imageKey(serverA, user).isEmpty(), "nothing registered yet: the overlay falls back to the avatar");

        store.setImage(user, new byte[] {1});                       // shared
        String shared = store.imageKey(user).orElseThrow();
        assertEquals(shared, store.imageKey(serverA, user).orElseThrow(), "no own picture: the shared one is used");

        assertTrue(store.setImage(user, serverA, new byte[] {2}));  // only for server A
        String own = store.imageKeyOf(user, serverA).orElseThrow();
        assertNotEquals(shared, own);
        assertEquals(own, store.imageKey(serverA, user).orElseThrow());
        assertEquals(shared, store.imageKey(serverB, user).orElseThrow(), "other servers still use the shared picture");

        assertTrue(store.removeImage(user, serverA));
        assertEquals(shared, store.imageKey(serverA, user).orElseThrow(), "back to the shared picture");
        assertTrue(store.readImage(own).isEmpty(), "the removed file is deleted");
        assertFalse(store.removeImage(user, serverA));
    }

    @Test
    void serverPicturesSurviveARestartAndAreLimited() throws Exception {
        StandeeStore store = new StandeeStore(dir);
        store.setImage(4, 555, new byte[] {7});
        assertEquals(store.imageKeyOf(4, 555), new StandeeStore(dir).imageKeyOf(4, 555));

        for (int i = 1; i < StandeeStore.MAX_SERVER_PICTURES; i++) {
            assertTrue(store.setImage(4, 9000 + i, new byte[] {1}));
        }
        assertFalse(store.setImage(4, 9999, new byte[] {1}), "over the limit");
        assertTrue(store.imageKeyOf(4, 9999).isEmpty());
        assertTrue(store.setImage(4, 555, new byte[] {8}), "replacing an existing one is always allowed");
        assertTrue(store.anyImageKey(4).isPresent());
    }

    @Test
    void theAddressSecretIsStableUntilReset() throws Exception {
        StandeeStore store = new StandeeStore(dir);
        String token = store.ensureToken(7);
        assertEquals(token, store.ensureToken(7));
        assertEquals(7L, store.userForToken(token).orElseThrow());
        assertTrue(token.length() >= 30);

        String fresh = store.resetToken(7);
        assertNotEquals(token, fresh);
        assertTrue(store.userForToken(token).isEmpty(), "the old address stops working");
        assertEquals(7L, store.userForToken(fresh).orElseThrow());
    }

    @Test
    void onlyAPersonWithAnAddressCanBeAnAutoStreamer() throws Exception {
        StandeeStore store = new StandeeStore(dir);
        store.setImage(5, new byte[] {1});
        assertFalse(store.isAutoStreamer(5), "a picture alone does not make the bot follow someone");
        store.ensureToken(5);
        assertTrue(store.isAutoStreamer(5));
        store.setAuto(5, false);
        assertFalse(store.isAutoStreamer(5));
        assertFalse(store.isAuto(5));
        store.setAuto(5, true);
        assertTrue(store.isAutoStreamer(5));
    }

    @Test
    void everythingSurvivesARestart() throws Exception {
        StandeeStore store = new StandeeStore(dir);
        store.setImage(9, new byte[] {9, 9});
        String token = store.ensureToken(9);
        store.setAuto(9, false);

        StandeeStore again = new StandeeStore(dir);
        assertEquals(store.imageKey(9), again.imageKey(9));
        assertEquals(9L, again.userForToken(token).orElseThrow());
        assertFalse(again.isAuto(9));
        assertArrayEquals(new byte[] {9, 9}, again.readImage(again.imageKey(9).orElseThrow()).orElseThrow());
    }

    @Test
    void pictureNamesThatAreNotKeysAreNeverReadFromDisk() throws Exception {
        StandeeStore store = new StandeeStore(dir);
        Files.writeString(dir.resolve("secret.png"), "x");
        assertTrue(store.readImage("../secret").isEmpty());
        assertTrue(store.readImage("secret").isEmpty());
        assertTrue(store.readImage(null).isEmpty());
    }
}
