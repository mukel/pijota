package ai.pijota.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class OAuthTokenStoreTest {

    private Path authFile;
    private Path backup;

    @BeforeEach
    void setUp() throws IOException {
        authFile = Path.of(System.getProperty("user.home"), ".pijota", "auth.json");
        backup = authFile.resolveSibling("auth.json.test-backup");
        Files.createDirectories(authFile.getParent());
        if (Files.exists(authFile)) {
            Files.move(authFile, backup, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(backup);
        }
        Files.deleteIfExists(authFile);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(authFile);
        if (Files.exists(backup)) {
            Files.move(backup, authFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

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
        var future = System.currentTimeMillis() + 3_600_000;
        var entry = new OAuthTokenStore.Entry("token", "refresh", future, "acc1", null, null);
        assertFalse(entry.isExpired());
    }

    @Test
    void entryStoresAllFields() {
        var entry = new OAuthTokenStore.Entry("at", "rt", 123L, "aid", "https://tokens", "cid");
        assertEquals("at", entry.access());
        assertEquals("rt", entry.refresh());
        assertEquals(123L, entry.expires());
        assertEquals("aid", entry.accountId());
        assertEquals("https://tokens", entry.tokenUrl());
        assertEquals("cid", entry.clientId());
    }

    @Test
    void entryEquality() {
        var a = new OAuthTokenStore.Entry("x", "y", 1, "z", null, null);
        var b = new OAuthTokenStore.Entry("x", "y", 1, "z", null, null);
        assertEquals(a, b);
    }

    @Test
    void entryAccessorForNullFields() {
        var entry = new OAuthTokenStore.Entry("token", null, 0, null, null, null);
        assertNull(entry.refresh());
        assertNull(entry.accountId());
    }

    @Test
    void saveAndLoadAccessToken() {
        var store = new OAuthTokenStore();
        store.save("openai", "access-123", "refresh-123", System.currentTimeMillis() + 3_600_000, "acc1",
                                        "https://tokens", "client1");

        var reloaded = new OAuthTokenStore();
        assertEquals("access-123", reloaded.getAccessToken("openai"));
        assertEquals("refresh-123", reloaded.getRefreshToken("openai"));
        assertEquals("acc1", reloaded.getAccountId("openai"));
    }

    @Test
    void getAccessTokenReturnsNullForUnknownProvider() {
        var store = new OAuthTokenStore();
        assertNull(store.getAccessToken("unknown"));
    }

    @Test
    void expiredTokenWithoutRefreshReturnsNull() {
        var store = new OAuthTokenStore();
        long past = System.currentTimeMillis() - 60_000;
        store.save("openai", "access-123", null, past, "acc1", null, null);
        assertNull(store.getAccessToken("openai"));
    }

    @Test
    void removeDeletesEntry() {
        var store = new OAuthTokenStore();
        store.save("openai", "access-123", "refresh-123", System.currentTimeMillis() + 3_600_000, "acc1", null, null);
        store.remove("openai");
        assertNull(store.getAccessToken("openai"));
        var reloaded = new OAuthTokenStore();
        assertNull(reloaded.getAccessToken("openai"));
    }

    @Test
    void loadAllRecoversFromCorruptedFile() throws IOException {
        Files.createDirectories(authFile.getParent());
        Files.writeString(authFile, "not json");
        var store = new OAuthTokenStore();
        assertNull(store.getAccessToken("any"));
    }

    @Test
    void expiredTokenWithRefreshButNoTokenUrlReturnsNull() {
        var store = new OAuthTokenStore();
        long past = System.currentTimeMillis() - 60_000;
        store.save("openai", "access-123", "refresh-123", past, "acc1", null, "client1");
        assertNull(store.getAccessToken("openai"));
    }

    @Test
    void accessorsReturnNullForUnknownProvider() {
        var store = new OAuthTokenStore();
        assertNull(store.getRefreshToken("unknown"));
        assertNull(store.getAccountId("unknown"));
    }
}
