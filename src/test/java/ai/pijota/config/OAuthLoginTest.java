package ai.pijota.config;

import ai.pijota.testkit.FakeHttpClient;
import ai.pijota.util.Util;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class OAuthLoginTest {

    @Test
    void loginRequestsADeviceCodeAnnouncesItThenPollsForTheToken() throws Exception {
        var device = "{\"device_code\":\"dc\",\"user_code\":\"UC-123\",\"verification_uri\":\"https://verify\",\"expires_in\":300}";
        var token = "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}";
        var client = FakeHttpClient.sequence(device, token); // 1st call → device, 2nd → token
        var out = new ArrayList<String>();

        var result = OAuthLogin.login("https://dev", "https://tok", "cid", "scope", out::add, client);

        assertEquals("at", result.accessToken());
        assertEquals("rt", result.refreshToken());
        assertTrue(out.stream().anyMatch(l -> l.contains("UC-123")), "the user code is announced: " + out);
    }

    @Test
    void loginThrowsWhenTheDeviceCodeRequestFails() {
        var client = FakeHttpClient.status(500, "boom");
        assertThrows(Exception.class, () -> OAuthLogin.login("d", "t", "c", "s", s -> {}, client));
    }

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
    void urlEncodeEncodesSpaces() {
        assertEquals("hello+world", OAuthLogin.urlEncode("hello world"));
    }

    @Test
    void urlEncodeEncodesSpecialChars() {
        assertEquals("hello%21", OAuthLogin.urlEncode("hello!"));
    }

    @Test
    void urlEncodeHandlesEmptyString() {
        assertEquals("", OAuthLogin.urlEncode(""));
    }

    @Test
    void clickableLinkContainsUrlAndText() {
        String link = OAuthLogin.clickableLink("https://example.com", "Click here");
        assertTrue(link.contains("https://example.com"));
        assertTrue(link.contains("Click here"));
    }

    @Test
    void buildTokenResultParsesAllFields() throws Exception {
        var json = Util.MAPPER.createObjectNode();
        json.put("access_token", "at-secret");
        json.put("refresh_token", "rt-secret");
        json.put("expires_in", 600);
        json.put("account_id", "acc-42");

        var token = OAuthLogin.buildTokenResult(json, 0L);
        assertEquals("at-secret", token.accessToken());
        assertEquals("rt-secret", token.refreshToken());
        assertEquals(600_000L, token.expiresAt());
        assertEquals("acc-42", token.accountId());
    }

    @Test
    void buildTokenResultExtractsAccountIdFromIdToken() throws Exception {
        // ChatGPT/Codex returns the account id only inside the id_token JWT, not as a top-level field.
        var auth = Util.MAPPER.createObjectNode();
        auth.put("chatgpt_account_id", "acc-from-idtoken");
        var claims = Util.MAPPER.createObjectNode();
        claims.set("https://api.openai.com/auth", auth);
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                                        .encodeToString(Util.MAPPER.writeValueAsBytes(claims));

        var json = Util.MAPPER.createObjectNode();
        json.put("access_token", "at");
        json.put("id_token", "header." + payload + ".sig");

        assertEquals("acc-from-idtoken", OAuthLogin.buildTokenResult(json, 0L).accountId());
    }

    @Test
    void buildTokenResultDefaultsExpiresIn() throws Exception {
        var json = Util.MAPPER.createObjectNode();
        json.put("access_token", "at");

        var token = OAuthLogin.buildTokenResult(json, 100_000L);
        assertEquals("at", token.accessToken());
        assertEquals(100_000L + (3600 * 1000L), token.expiresAt());
        assertNull(token.refreshToken());
        assertNull(token.accountId());
    }

    // ---- chatgptAccountIdFromIdToken: JWT payload parsing (auth) ----

    private static String jwt(String payloadJson) {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                                        .encodeToString(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    @Test
    void chatgptAccountIdExtractsTheClaimFromAValidToken() {
        var token = jwt("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acc-123\"}}");
        assertEquals("acc-123", OAuthLogin.chatgptAccountIdFromIdToken(token));
    }

    @Test
    void chatgptAccountIdReturnsNullForMalformedOrClaimlessTokens() {
        assertNull(OAuthLogin.chatgptAccountIdFromIdToken("not-a-jwt"), "not three dot-separated parts");
        assertNull(OAuthLogin.chatgptAccountIdFromIdToken("a.b.c"), "undecodable payload → caught → null");
        assertNull(OAuthLogin.chatgptAccountIdFromIdToken(jwt("{\"sub\":\"x\"}")), "valid JWT but no auth claim");
    }
}
