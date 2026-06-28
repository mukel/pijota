package ai.pijota.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the no-Chrome, pure surface of {@link BrowserSession}: URL handling, Chrome discovery, and helpers. */
class BrowserSessionTest {

    @Test
    void shouldReapOnlyWhenAliveAndIdlePastTheTimeout() {
        long idle = 300_000_000_000L; // 300s
        long now = 1_000_000_000_000L;
        assertTrue(BrowserSession.shouldReap(true, now - idle - 1, now, idle), "alive + idle past timeout → reap");
        assertFalse(BrowserSession.shouldReap(true, now - 1, now, idle), "recently used → keep");
        assertFalse(BrowserSession.shouldReap(false, now - idle - 1, now, idle), "already closed → nothing to reap");
        assertFalse(BrowserSession.shouldReap(true, now - idle - 1, now, 0), "timeout disabled (0) → never reap");
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
    void normalizeUrlRejectsNonHttpSchemes() {
        // a real browser must not be steered into file:// (local-file read) or other schemes.
        assertThrows(IllegalArgumentException.class, () -> BrowserSession.normalizeUrl("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> BrowserSession.normalizeUrl("ftp://host/x"));
    }

    @Test
    void normalizeUrlRejectsInternalAddressesForSsrf() {
        // loopback, link-local (169.254.169.254 cloud metadata), private, wildcard — SSRF-blocked.
        for (String u : new String[] {"http://127.0.0.1:9333/json/version", "http://localhost/x",
                "http://169.254.169.254/latest/meta-data/", "http://10.0.0.1", "http://192.168.1.1", "http://0.0.0.0"})
            assertThrows(IllegalArgumentException.class, () -> BrowserSession.normalizeUrl(u),
                                            u + " should be blocked");
    }

    @Test
    void findChromeHonorsTheExplicitOverride() {
        assertEquals(Path.of("/opt/my/chrome"), CdpBackend.findChrome("/opt/my/chrome"));
    }

    @Test
    void findChromeWithoutOverrideScansCommonLocationsAndPath() {
        Path chrome = CdpBackend.findChrome(null); // exercises the candidate-list build + PATH scan + firstExecutable
        org.junit.jupiter.api.Assumptions.assumeTrue(chrome != null, "no chrome on this machine");
        assertTrue(java.nio.file.Files.isExecutable(chrome), "the discovered chrome is executable: " + chrome);
    }

    @Test
    void findChromeReturnsNullWhenNoCandidateResolves() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                                        ai.pijota.util.Executables.anyAvailable(List.of("google-chrome-stable",
                                                                        "google-chrome", "chromium", "chrome")),
                                        "chrome is installed, so the not-found path can't be exercised here");
        assertNull(CdpBackend.findChrome(null), "no chrome anywhere → null");
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
