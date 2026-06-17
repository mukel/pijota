package ai.pijota.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the no-Chrome, pure surface of {@link BrowserSession}: URL handling, Chrome discovery, and helpers. */
class BrowserSessionTest {

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
        assertEquals(Path.of("/opt/my/chrome"), CdpBackend.findChrome("/opt/my/chrome"));
    }

    @Test
    void firstExecutablePicksTheFirstMatch() {
        var candidates = List.of(Path.of("/nope/a"), Path.of("/yes/b"), Path.of("/yes/c"));
        assertEquals(Path.of("/yes/b"), CdpBackend.firstExecutable(candidates, p -> p.startsWith("/yes")));
        assertNull(CdpBackend.firstExecutable(candidates, p -> false));
    }

    @Test
    void createBuildsChromeWithoutTouchingAnyDriverAndRejectsUnknownBrowsers() {
        // chrome path builds a CdpBackend with no I/O (no launch, no driver lookup) → safe to construct here.
        assertNotNull(BrowserSession.create("chrome", 9333, Path.of("/tmp/p"), null, null, () -> true));
        assertNotNull(BrowserSession.create(null, 9333, Path.of("/tmp/p"), null, null, () -> true), "null → chrome");
        assertThrows(IllegalArgumentException.class, () -> BrowserSession.create("netscape", 9333, Path.of("/tmp/p"),
                                        null, null, () -> true));
    }

    @Test
    void strHandlesNullAndMissingNodes() {
        assertEquals("", BrowserSession.str(null));
        assertEquals("", BrowserSession.str(com.fasterxml.jackson.databind.node.NullNode.getInstance()));
        assertEquals("x", BrowserSession.str(com.fasterxml.jackson.databind.node.TextNode.valueOf("x")));
    }
}
