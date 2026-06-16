package ai.pijota.browser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the no-Chrome surface of {@link BrowserSession}: CDP JSON-RPC routing, Chrome discovery, and helpers. */
class BrowserSessionTest {

    private BrowserSession session() {
        return new BrowserSession(9999, Path.of("/tmp/pijota-test-profile"), null, null, () -> true);
    }

    @Test
    void resolvesCdpResultToTheWaitingCaller() throws Exception {
        var s = session();
        CompletableFuture<JsonNode> fut = s.awaitId(7);
        s.handleMessage("{\"id\":7,\"result\":{\"result\":{\"value\":\"hello\"}}}");
        JsonNode result = fut.get(1, TimeUnit.SECONDS);
        assertEquals("hello", result.path("result").path("value").asText());
    }

    @Test
    void surfacesCdpErrorsAsExceptions() {
        var s = session();
        CompletableFuture<JsonNode> fut = s.awaitId(8);
        s.handleMessage("{\"id\":8,\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
        var ex = assertThrows(ExecutionException.class, () -> fut.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("boom"));
    }

    @Test
    void ignoresEventsWithoutAnId() {
        var s = session();
        CompletableFuture<JsonNode> fut = s.awaitId(9);
        s.handleMessage("{\"method\":\"Page.loadEventFired\",\"params\":{}}");
        assertFalse(fut.isDone(), "an unsolicited CDP event must not complete a pending request");
    }

    @Test
    void malformedFrameDoesNotThrow() {
        assertDoesNotThrow(() -> session().handleMessage("not json"));
    }

    @Test
    void googleSearchUrlEncodesTheQuery() {
        assertEquals("https://www.google.com/search?hl=en&q=java+records+%26+sealed",
                                        BrowserSession.googleSearchUrl("java records & sealed"));
    }

    @Test
    void normalizeUrlPrependsHttpsWhenSchemeMissing() {
        assertEquals("https://example.com", BrowserSession.normalizeUrl("example.com"));
        assertEquals("https://example.com/x", BrowserSession.normalizeUrl("  example.com/x  "));
        assertEquals("https://a.com", BrowserSession.normalizeUrl("https://a.com")); // already absolute → unchanged
        assertEquals("http://a.com", BrowserSession.normalizeUrl("http://a.com"));
        assertThrows(IllegalArgumentException.class, () -> BrowserSession.normalizeUrl("  "));
    }

    @Test
    void findChromeHonorsTheExplicitOverride() {
        assertEquals(Path.of("/opt/my/chrome"), BrowserSession.findChrome("/opt/my/chrome"));
    }

    @Test
    void firstExecutablePicksTheFirstMatch() {
        var candidates = List.of(Path.of("/nope/a"), Path.of("/yes/b"), Path.of("/yes/c"));
        assertEquals(Path.of("/yes/b"), BrowserSession.firstExecutable(candidates, p -> p.startsWith("/yes")));
        assertNull(BrowserSession.firstExecutable(candidates, p -> false));
    }

    @Test
    void strHandlesNullAndMissingNodes() {
        assertEquals("", BrowserSession.str(null));
        assertEquals("", BrowserSession.str(com.fasterxml.jackson.databind.node.NullNode.getInstance()));
        assertEquals("x", BrowserSession.str(com.fasterxml.jackson.databind.node.TextNode.valueOf("x")));
    }
}
