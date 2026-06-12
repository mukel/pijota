package ai.pijota.config;

import org.junit.jupiter.api.Test;

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
}
