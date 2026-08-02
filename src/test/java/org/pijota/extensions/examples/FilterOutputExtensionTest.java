package org.pijota.extensions.examples;

import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.messages.Message;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    void initRegistersSlashCommandAndHandlers() {
        var api = new FakeExtensionAPI();
        var ext = new FilterOutputExtension();
        ext.init(api);
        assertNotNull(api.slashCommand("filter-output"));
        assertEquals(1, api.listeners(ExtensionEvent.Input.class).size());
        assertEquals(1, api.listeners(ExtensionEvent.BeforeProviderRequest.class).size());
    }

    @Test
    void onReadyReadsFlagAndReflectsItInTheStatusLine() {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("filter-secrets", "false");
        var ext = new FilterOutputExtension();
        var api = new FakeExtensionAPI(runtime);
        ext.init(api);
        ext.onReady(runtime);
        // flag=false → redaction off → the live status supplier reports "visible"
        assertTrue(String.join("", api.statusLineSuppliers().getFirst().get()).contains("visible"));
    }

    @Test
    void toggleSlashCommandChangesStatus() {
        var runtime = new FakeExtensionRuntime();
        var api = new FakeExtensionAPI(runtime);
        var ext = new FilterOutputExtension();
        ext.init(api);
        ext.onReady(runtime);
        var status = api.statusLineSuppliers().getFirst();
        var before = status.get();
        api.slashCommand("filter-output").handler().accept("");
        assertNotEquals(before, status.get());
    }

    @Test
    void onInputRedactsWhenEnabled() {
        var api = new FakeExtensionAPI();
        var ext = new FilterOutputExtension();
        ext.init(api);
        var result = api.filterInput("my key is sk-abc123def456");
        assertTrue(result.text().contains("[REDACTED]"));
    }

    @Test
    void onInputPassesThroughWhenDisabled() {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("filter-secrets", "false");
        var api = new FakeExtensionAPI(runtime);
        var ext = new FilterOutputExtension();
        ext.init(api);
        ext.onReady(runtime);
        var result = api.filterInput("my key is sk-abc123def456");
        assertEquals("my key is sk-abc123def456", result.text());
    }

    @Test
    void onBeforeProviderRequestRedactsMessages() {
        var api = new FakeExtensionAPI();
        var ext = new FilterOutputExtension();
        ext.init(api);
        var messages = new ArrayList<Message>();
        messages.add(new Message.User("my key is sk-abc123def456"));
        messages.add(new Message.ToolResult("tc", "bash", "my key is sk-abc123def456", false));
        messages.add(new Message.Assistant("my key is sk-abc123def456", List.of(), null));
        api.fire(new ExtensionEvent.BeforeProviderRequest(messages));
        for (Message m : messages) {
            String content = switch (m) {
                case Message.User u -> u.content();
                case Message.ToolResult r -> r.content();
                case Message.Assistant a -> a.content();
                default -> null;
            };
            assertNotNull(content);
            assertTrue(content.contains("[REDACTED]"), content);
        }
    }

    @Test
    void onBeforeProviderRequestSkipsMessagesAlreadyRedacted() {
        var api = new FakeExtensionAPI();
        var ext = new FilterOutputExtension();
        ext.init(api);
        var messages = new ArrayList<Message>();
        messages.add(new Message.User("already [REDACTED]"));
        api.fire(new ExtensionEvent.BeforeProviderRequest(messages));
        assertEquals("already [REDACTED]", ((Message.User) messages.get(0)).content());
    }
}
