package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FilterOutputExtensionTest {

    @Test
    void redactNullReturnsNull() {
        assertNull(FilterOutputExtension.redactString(null));
    }

    @Test
    void redactBlankReturnsNull() {
        assertNull(FilterOutputExtension.redactString("  "));
    }

    @Test
    void redactOpenAiKey() {
        var result = FilterOutputExtension.redactString("my key is sk-abc123def456ghty");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
        assertFalse(result.contains("sk-abc"));
    }

    @Test
    void redactAwsKey() {
        var result = FilterOutputExtension.redactString("AKIA1234567890ABCDEF");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void redactGithubToken() {
        var result = FilterOutputExtension.redactString("token: ghp_123456789012345678901234567890123456");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void redactJwt() {
        var result = FilterOutputExtension.redactString("Bearer eyJhbGciOiJIUz.eyJzdWIiOiIxMjM0.N0nc0BfGzI");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void redactPrivateKeyBlock() {
        var text = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----";
        var result = FilterOutputExtension.redactString(text);
        assertNotNull(result);
        assertFalse(result.contains("PRIVATE KEY-----"), "private key block should be redacted, got: " + result);
    }

    @Test
    void redactEnvSecretLine() {
        var result = FilterOutputExtension.redactString("export API_KEY=sk-secret12345");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
        assertFalse(result.contains("sk-secret"));
    }

    @Test
    void redactLongBase64Value() {
        var result = FilterOutputExtension.redactString("some token=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij1234567890+/=");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void redactDoesNotChangeBenign() {
        var result = FilterOutputExtension.redactString("hello world");
        assertNull(result);
    }

    @Test
    void redactStripeKey() {
        var result = FilterOutputExtension.redactString("sk_test_1234567890ABCDEFGHIJKLMNO");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void redactGoogleApiKey() {
        var result = FilterOutputExtension.redactString("key: AIzaSyD-1234567890abcdefghijklmnopqrstuvwxyz1234");
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"));
    }
}
