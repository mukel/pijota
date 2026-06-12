package ai.pijota.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OAuthLoginTest {

    @Test
    void deviceCodeStoresFields() {
        var device = new OAuthLogin.DeviceCode("device123", "user456", "https://verify", 600);
        assertEquals("device123", device.deviceCode());
        assertEquals("user456", device.userCode());
        assertEquals("https://verify", device.verificationUri());
        assertEquals(600, device.expiresIn());
    }

    @Test
    void tokenResultStoresFields() {
        var token = new OAuthLogin.TokenResult("at", "rt", 1234567890L, "acc1");
        assertEquals("at", token.accessToken());
        assertEquals("rt", token.refreshToken());
        assertEquals(1234567890L, token.expiresAt());
        assertEquals("acc1", token.accountId());
    }

    @Test
    void authCodeStoresFields() {
        var auth = new OAuthLogin.AuthCode("code123", "verifier456");
        assertEquals("code123", auth.code());
        assertEquals("verifier456", auth.codeVerifier());
    }

    @Test
    void deviceCodeEquality() {
        var a = new OAuthLogin.DeviceCode("d", "u", "v", 1);
        var b = new OAuthLogin.DeviceCode("d", "u", "v", 1);
        assertEquals(a, b);
        assertNotEquals(a, new OAuthLogin.DeviceCode("x", "u", "v", 1));
    }

    @Test
    void tokenResultEquality() {
        var a = new OAuthLogin.TokenResult("at", "rt", 1L, "aid");
        var b = new OAuthLogin.TokenResult("at", "rt", 1L, "aid");
        assertEquals(a, b);
    }

    @Test
    void authCodeEquality() {
        var a = new OAuthLogin.AuthCode("c", "v");
        var b = new OAuthLogin.AuthCode("c", "v");
        assertEquals(a, b);
    }
}
