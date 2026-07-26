package org.pijota.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real {@link BrowserSession} end-to-end against {@link FakeChrome} (a fake CDP endpoint) — no browser
 * launched — covering attach/reuse, navigate, visit/search extraction, error surfacing, reconnect, and close.
 */
class BrowserSessionCdpTest {

    private static final Path PROFILE = Path.of(System.getProperty("java.io.tmpdir"), "pijota-fake-profile");

    private BrowserSession session(FakeChrome fake) {
        return session(fake, () -> true);
    }

    private BrowserSession session(FakeChrome fake, Callable<Boolean> confirm) {
        return new BrowserSession(fake.port(), PROFILE, null, null, confirm);
    }

    @Test
    void visitReturnsTheExtractedMarkdown() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            var s = session(fake);
            assertEquals(fake.pageExtract, s.visit("https://example.com"));
            assertEquals(1, fake.navigateCount.get());
            s.close();
        }
    }

    @Test
    void searchReturnsResultsAndSnapshotUnderAHeader() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            var s = session(fake);
            String md = s.search("chrome devtools");
            assertTrue(md.startsWith("# Search: chrome devtools"), md);
            assertTrue(md.contains("## Results"), md);
            assertTrue(md.contains("## Page text"), md);
            s.close();
        }
    }

    @Test
    void attachesToLiveDevtoolsWithoutLaunchingOrConfirming() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            var confirmed = new AtomicBoolean(false);
            var s = session(fake, () -> {
                confirmed.set(true);
                return true;
            });
            s.visit("https://example.com");
            assertFalse(confirmed.get(), "DevTools already up → must reuse it, not confirm+launch");
            s.close();
        }
    }

    @Test
    void normalizeUrlIsAppliedBeforeNavigating() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            var s = session(fake);
            s.visit("example.com/path"); // no scheme
            assertEquals("https://example.com/path", fake.currentUrl);
            s.close();
        }
    }

    @Test
    void navigationErrorIsSurfaced() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            fake.navigateError = "net::ERR_NAME_NOT_RESOLVED";
            var s = session(fake);
            var ex = assertThrows(Exception.class, () -> s.visit("https://nope.invalid"));
            assertTrue(ex.getMessage().contains("net::ERR_NAME_NOT_RESOLVED"), ex.getMessage());
            s.close();
        }
    }

    @Test
    void readerModeIsPreferredWhenThePageIsAnArticle() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            fake.readerJson = "{\"title\":\"Headline\",\"byline\":\"By A. Writer\","
                                            + "\"content\":\"<p>The article body text.</p>\"}";
            var s = session(fake);
            String md = s.visit("https://blog/post");
            assertTrue(md.contains("# Headline"), md);
            assertTrue(md.contains("By A. Writer"), md);
            assertTrue(md.contains("The article body text."), md);
            assertFalse(md.contains("hello world"), "must use the reader article, not the DOM-walk fallback: " + md);
            s.close();
        }
    }

    @Test
    void fallsBackToTheDomWalkWhenNotAnArticle() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            fake.readerJson = null; // Readability returns null → tier-2 visible-only walk
            var s = session(fake);
            assertEquals(fake.pageExtract, s.visit("https://app/dashboard"));
            s.close();
        }
    }

    @Test
    void blankExtractionFallsBackToFullDomConversion() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            fake.pageExtract = ""; // visible-only walk yields nothing → fall back to outerHTML→markdown
            var s = session(fake);
            String md = s.visit("https://example.com");
            assertTrue(md.contains("fallback"), "expected htmlToMarkdown of the outerHTML fallback, got: " + md);
            s.close();
        }
    }

    @Test
    void reconnectsAfterTheSocketDrops() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            var s = session(fake);
            assertEquals(fake.pageExtract, s.visit("https://a.com"));
            fake.dropConnection();
            Thread.sleep(500); // let the client observe the close → connected=false
            assertEquals(fake.pageExtract, s.visit("https://b.com"), "should transparently reconnect");
            assertEquals(2, fake.navigateCount.get());
            s.close();
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            var s = session(fake);
            s.visit("https://a.com");
            assertDoesNotThrow(s::close);
            assertDoesNotThrow(s::close);
        }
    }

    @Test
    void declinedLaunchThrowsWithoutSpawningChrome() {
        // point at a dead port (no DevTools) so ensureReady would launch — but confirm says no → it must not spawn.
        var s = new BrowserSession(1, PROFILE, "/nonexistent/chrome", null, () -> false);
        var ex = assertThrows(Exception.class, () -> s.visit("https://example.com"));
        assertTrue(ex.getMessage().toLowerCase().contains("declined"), ex.getMessage());
    }
}
