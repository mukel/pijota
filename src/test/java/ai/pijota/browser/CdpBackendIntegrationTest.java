package ai.pijota.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-browser integration coverage of {@link CdpBackend}: launches an actual Chrome, attaches over the DevTools
 * protocol, navigates and evaluates JS end-to-end. Skipped (not failed) when Chrome isn't installed, so it stays green
 * on machines without a browser; deterministic where Chrome is present because it only touches {@code about:blank}.
 */
class CdpBackendIntegrationTest {

    private static boolean chromeAvailable() {
        try {
            return CdpBackend.findChrome(null) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @Timeout(90)
    void launchesChromeAttachesNavigatesAndEvaluatesJs() throws Exception {
        assumeTrue(chromeAvailable(), "chrome not installed");
        int port = freePort();
        Path profile = Files.createTempDirectory("pijota-cdp-test");
        var backend = new CdpBackend(port, profile, null, m -> {}, () -> true, 800, 600, List.of());
        try {
            backend.start();
            assertTrue(backend.isAlive(), "the launched browser is attached + alive");

            backend.navigate("about:blank");
            assertEquals(42, backend.evaluate("1 + 41").asInt(), "evaluates arithmetic over CDP");
            assertEquals("HELLO", backend.evaluate("'hello'.toUpperCase()").asText(), "evaluates a string expression");
            assertEquals(3, backend.evaluate("({a:1,b:[2,3]})").path("b").get(1).asInt(), "returns structured values");
        } finally {
            backend.close();
        }
        assertFalse(backend.isAlive(), "close() tears the browser down");
    }

    @Test
    @Timeout(90)
    void navigatesToADocumentRunsInitScriptsAndReadsTheDom() throws Exception {
        assumeTrue(chromeAvailable(), "chrome not installed");
        int port = freePort();
        Path profile = Files.createTempDirectory("pijota-cdp-dom");
        // an init script runs on every new document (covers applyInitScripts / addScriptToEvaluateOnNewDocument)
        var backend = new CdpBackend(port, profile, null, m -> {}, () -> true, 800, 600,
                                        List.of("window.__pijotaInit = 99;"));
        try {
            backend.start();
            backend.navigate("data:text/html,<html><head><title>T</title></head>"
                                            + "<body><h1 id='x'>Hello DOM</h1></body></html>");
            assertEquals("Hello DOM", backend.evaluate("document.getElementById('x').textContent").asText(),
                                            "reads the rendered DOM");
            assertEquals("T", backend.evaluate("document.title").asText());
            assertEquals(99, backend.evaluate("window.__pijotaInit").asInt(), "the init script ran on the new document");
        } finally {
            backend.close();
        }
    }

    @Test
    @Timeout(90)
    void extractsTextFromADigitalPdfViaTheBundledPdfJs() throws Exception {
        assumeTrue(chromeAvailable(), "chrome not installed");
        int port = freePort();
        Path profile = Files.createTempDirectory("pijota-pdf");
        var session = new BrowserSession(new CdpBackend(port, profile, null, m -> {}, () -> true, 800, 600, List.of()));
        try {
            String b64 = Base64.getEncoder().encodeToString(minimalPdf("Hello PDF World"));
            String md = session.extractPdf(b64); // navigates about:blank, injects PDF.js, parses in-page
            assertTrue(md.contains("Hello PDF World"), "the digital PDF's text layer is extracted: " + md);
        } finally {
            session.close();
        }
    }

    /** Builds a minimal single-page PDF with one text run, with a correct cross-reference table. */
    private static byte[] minimalPdf(String text) {
        var objs = new ArrayList<String>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objs.add("<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >>"
                                        + " /MediaBox [0 0 300 200] /Contents 5 0 R >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        String content = "BT /F1 18 Tf 20 100 Td (" + text + ") Tj ET";
        objs.add("<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream");

        var sb = new StringBuilder("%PDF-1.4\n");
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < objs.size(); i++) {
            offsets.add(sb.length());
            sb.append(i + 1).append(" 0 obj\n").append(objs.get(i)).append("\nendobj\n");
        }
        int xref = sb.length();
        sb.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets)
            sb.append(String.format("%010d 00000 n \n", off));
        sb.append("trailer\n<< /Root 1 0 R /Size ").append(objs.size() + 1).append(" >>\nstartxref\n").append(xref)
                                        .append("\n%%EOF");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
