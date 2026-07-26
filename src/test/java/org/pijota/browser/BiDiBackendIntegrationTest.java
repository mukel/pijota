package org.pijota.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pijota.util.Executables;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-browser integration coverage of {@link BiDiBackend}: launches Firefox via geckodriver, speaks WebDriver BiDi,
 * navigates and evaluates JS. Skipped (not failed) when geckodriver/Firefox aren't installed; deterministic where they
 * are because it only renders an inline {@code data:} document.
 */
class BiDiBackendIntegrationTest {

    private static boolean geckodriverAvailable() {
        return Executables.isAvailable("geckodriver");
    }

    private static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @Timeout(120)
    void firefoxBiDiLaunchesNavigatesAndEvaluates() throws Exception {
        assumeTrue(geckodriverAvailable(), "geckodriver/firefox not installed");
        int port = freePort();
        BiDiBackend backend = BiDiBackends.firefox(port, null, m -> {}, () -> true, 800, 600,
                                        List.of("window.__pijotaInit = 5;"));
        try {
            backend.start();
            assertTrue(backend.isAlive(), "Firefox launched + the BiDi session is live");

            backend.navigate("data:text/html,<html><head><title>BT</title></head>"
                                            + "<body><h1 id='x'>Hi BiDi</h1></body></html>");
            assertEquals("Hi BiDi", backend.evaluate("document.getElementById('x').textContent").asText(),
                                            "reads the rendered DOM over BiDi");
            assertEquals(7, backend.evaluate("3 + 4").asInt(), "evaluates arithmetic over BiDi");
            assertEquals(5, backend.evaluate("window.__pijotaInit").asInt(), "the preload init script ran");
        } finally {
            backend.close();
        }
    }
}
