package org.pijota.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OAuthTokenStoreTest {

    @Test
    void entryNotExpiredWhenExpiresIsZero() {
        var entry = new OAuthTokenStore.Entry("token", "refresh", 0, "acc1", null, null);
        assertFalse(entry.isExpired());
    }

    @Test
    void entryExpiredWhenPastTimestamp() {
        var past = System.currentTimeMillis() - 60_000;
        var entry = new OAuthTokenStore.Entry("token", "refresh", past, "acc1", null, null);
        assertTrue(entry.isExpired());
    }

    @Test
    void entryNotExpiredWhenFutureTimestamp() {
        var future = System.currentTimeMillis() + 60_000;
        var entry = new OAuthTokenStore.Entry("token", "refresh", future, "acc1", null, null);
        assertFalse(entry.isExpired());
    }

    @Test
    void saveAndRetrieveToken(@TempDir Path tmp) {
        var authFile = tmp.resolve("auth.json");
        var store = new OAuthTokenStore(authFile);
        store.save("openai", "access-1", "refresh-1", 0, "acc-1", null, null);

        assertEquals("access-1", store.getAccessToken("openai"));
        assertEquals("refresh-1", store.getRefreshToken("openai"));
        assertEquals("acc-1", store.getAccountId("openai"));
    }

    @Test
    void removeClearsToken(@TempDir Path tmp) {
        var authFile = tmp.resolve("auth.json");
        var store = new OAuthTokenStore(authFile);
        store.save("openai", "access-1", "refresh-1", 0, null, null, null);
        store.remove("openai");

        assertNull(store.getAccessToken("openai"));
    }

    @Test
    void returnsNullForUnknownProvider(@TempDir Path tmp) {
        var authFile = tmp.resolve("auth.json");
        var store = new OAuthTokenStore(authFile);
        assertNull(store.getAccessToken("unknown"));
        assertNull(store.getRefreshToken("unknown"));
    }

    @Test
    void expiredTokenWithoutRefreshReturnsNull(@TempDir Path tmp) {
        var authFile = tmp.resolve("auth.json");
        var store = new OAuthTokenStore(authFile);
        var past = System.currentTimeMillis() - 60_000;
        store.save("openai", "access-1", null, past, null, null, null);

        assertNull(store.getAccessToken("openai"));
    }

    @Test
    void tokensSurviveAPersistenceRoundTrip(@TempDir Path tmp) {
        var authFile = tmp.resolve("auth.json");
        new OAuthTokenStore(authFile).save("openai", "access-1", "refresh-1", 0, "acc-1", "https://t", "cid");

        var reloaded = new OAuthTokenStore(authFile); // reads the file written by save()
        assertEquals("access-1", reloaded.getAccessToken("openai"));
        assertEquals("refresh-1", reloaded.getRefreshToken("openai"));
        assertEquals("acc-1", reloaded.getAccountId("openai"));
    }

    @Test
    void saveOverwritesAnExistingProvider(@TempDir Path tmp) {
        var store = new OAuthTokenStore(tmp.resolve("auth.json"));
        store.save("openai", "old", "r", 0, null, null, null);
        store.save("openai", "new", "r2", 0, null, null, null);
        assertEquals("new", store.getAccessToken("openai"));
        assertEquals("r2", store.getRefreshToken("openai"));
    }

    @Test
    void loadIsResilientToMissingAndCorruptFiles(@TempDir Path tmp) throws Exception {
        assertNull(new OAuthTokenStore(tmp.resolve("absent.json")).getAccessToken("openai"));

        var corrupt = tmp.resolve("corrupt.json");
        Files.writeString(corrupt, "{ this is not valid json");
        assertDoesNotThrow(() -> new OAuthTokenStore(corrupt).getAccessToken("openai"));
    }
}
