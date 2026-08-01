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
 * Validates the WebDriver BiDi backend against a real Firefox driven by geckodriver, using local fixture pages (no
 * network). Opt-in: launches a visible browser, so it runs only when {@code PIJOTA_BROWSER_IT=1} and geckodriver is
 * installed (on {@code PATH} or {@code PIJOTA_GECKODRIVER}). This is the end-to-end check the {@code BiDiBackendTest}
 * unit tests can't do, in particular the {@code script.addPreloadScript} Readability injection and {@code setViewport}.
 */
@Tag("live")
class BrowserSessionLiveFirefoxTest {

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
    void domWalkExtractsVisibleContentFromRealFirefox() throws Exception {
        String md = render(PAGE);
        assertTrue(md.contains("# Fixture Title"), md);
        assertTrue(md.contains("# Main Heading"), md);
        assertTrue(md.contains("[visit link](https://example.org/target)"), md);
        assertTrue(md.contains("```") && md.contains("code line one"), md);
        assertFalse(md.contains("SECRET_HIDDEN_TEXT"), "display:none content must be filtered: " + md);
    }

    @Test
    void readerModeExtractsTheArticleFromRealFirefox() throws Exception {
        String md = render(ARTICLE);
        assertTrue(md.contains("UNIQUE_ARTICLE_PHRASE"), md); // Readability injected via script.addPreloadScript worked
        assertFalse(md.contains("ASIDE_AD_JUNK"), "reader mode must drop the aside/ad chrome: " + md);
    }

    /** Serves {@code html} on loopback, drives a real Firefox via BiDi to visit it, returns the extracted markdown. */
    private static String render(String html) throws Exception {
        assumeTrue("1".equals(System.getenv("PIJOTA_BROWSER_IT")), "set PIJOTA_BROWSER_IT=1 to run");
        assumeTrue(geckodriverAvailable(), "geckodriver not installed (PATH or PIJOTA_GECKODRIVER)");
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
        Path profile = Files.createTempDirectory("pijota-it-ff-profile");
        var session = BrowserSession.create("firefox", freePort(), profile, null, m -> System.err.println("[it] " + m),
                                        () -> true);
        try {
            return session.visit("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                                            + fixture.getAddress().getPort() + "/");
        } finally {
            session.close();
            fixture.stop(0);
        }
    }

    private static boolean geckodriverAvailable() {
        try {
            BiDiBackends.resolveDriver(null, "pijota.browse.geckodriver", "PIJOTA_GECKODRIVER", "geckodriver");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
