package org.pijota.browser;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Validates the real extraction JS against an actual Chrome over CDP, using local fixture pages (no network). Opt-in:
 * launches a visible browser, so it runs only when {@code PIJOTA_BROWSER_IT=1} and Chrome is present. The deterministic
 * {@link BrowserSessionCdpTest} covers the orchestration in the normal suite.
 */
@Tag("live")
class BrowserSessionLiveChromeTest {

    /** Short, non-article page → Readability declines → the visible-only DOM walk (tier 2) handles it. */
    private static final String PAGE = """
                                    <!doctype html><html><head><title>Fixture Title</title></head><body>
                                      <nav style="display:none">HIDDEN_NAV_TEXT</nav>
                                      <h1>Main Heading</h1>
                                      <p>Intro text with a <a href="https://example.org/target">visit link</a> inside.</p>
                                      <pre>code line one
                                    code line two</pre>
                                      <div class="comment-body">A user comment here</div>
                                      <div style="display:none">SECRET_HIDDEN_TEXT</div>
                                    </body></html>
                                    """;

    /** A real article → Readability ("reader mode", tier 1) extracts the body and drops nav/aside chrome. */
    private static final String ARTICLE = """
                                    <!doctype html><html><head><title>Reader Article</title></head><body>
                                      <nav>NAV_JUNK home about contact login signup</nav>
                                      <aside>ASIDE_AD_JUNK sponsored buy now limited offer</aside>
                                      <article>
                                        <h1>The Real Headline</h1>
                                        <p>UNIQUE_ARTICLE_PHRASE: reader mode should pull this article body out of the page while
                                           discarding the surrounding navigation and advertising chrome around it entirely.</p>
                                        <p>A second substantial paragraph with enough words that Mozilla Readability scores this
                                           document as readerable and returns its cleaned article content for conversion to markdown.</p>
                                        <p>A third paragraph so the extracted text comfortably exceeds the minimum length threshold
                                           the wrapper enforces before it trusts the reader-mode result over the DOM walk fallback.</p>
                                      </article>
                                      <footer>FOOTER_JUNK copyright</footer>
                                    </body></html>
                                    """;

    @Test
    void domWalkExtractsVisibleContentFromRealChrome() throws Exception {
        String md = render(PAGE);
        assertTrue(md.contains("# Fixture Title"), md); // page <title>
        assertTrue(md.contains("# Main Heading"), md); // h1 → #
        assertTrue(md.contains("[visit link](https://example.org/target)"), md); // inline link → markdown
        assertTrue(md.contains("```") && md.contains("code line one"), md); // <pre> → fenced code
        assertTrue(md.contains("A user comment here"), md); // comment-body selector
        assertFalse(md.contains("SECRET_HIDDEN_TEXT"), "display:none content must be filtered: " + md);
        assertFalse(md.contains("HIDDEN_NAV_TEXT"), "display:none nav must be filtered: " + md);
    }

    @Test
    void readerModeExtractsTheArticleAndDropsChromeFromRealChrome() throws Exception {
        String md = render(ARTICLE);
        assertTrue(md.contains("Reader Article"), md); // document title (Readability's article.title)
        assertTrue(md.contains("The Real Headline"), md); // article <h1> kept in the content
        assertTrue(md.contains("UNIQUE_ARTICLE_PHRASE"), md); // article body kept
        assertFalse(md.contains("ASIDE_AD_JUNK"), "reader mode must drop the aside/ad chrome: " + md);
        assertFalse(md.contains("NAV_JUNK"), "reader mode must drop the nav chrome: " + md);
    }

    /** Serves {@code html} on loopback, drives a real Chrome to visit it, and returns the extracted markdown. */
    private static String render(String html) throws Exception {
        assumeTrue("1".equals(System.getenv("PIJOTA_BROWSER_IT")), "set PIJOTA_BROWSER_IT=1 to run");
        assumeTrue(CdpBackend.findChrome(null) != null, "no Chrome/Chromium installed");
        HttpServer fixture = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fixture.createContext("/", ex -> {
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        fixture.start();
        Path profile = Files.createTempDirectory("pijota-it-profile");
        var session = new BrowserSession(freePort(), profile, null, m -> System.err.println("[it] " + m), () -> true);
        try {
            return session.visit("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                                            + fixture.getAddress().getPort() + "/");
        } finally {
            session.close();
            fixture.stop(0);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
