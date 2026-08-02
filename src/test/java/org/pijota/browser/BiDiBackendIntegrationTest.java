package org.pijota.browser;

import org.pijota.util.Executables;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-browser integration coverage of {@link BiDiBackend}: launches Firefox via geckodriver, speaks WebDriver BiDi,
 * navigates and evaluates JS. Opt-in ({@code PIJOTA_BROWSER_IT=1}) and tagged {@code live}, like every other test here
 * that starts a browser: a runner with the binaries installed may still be unable to launch them, and that should skip
 * rather than fail. Run with {@code make test-live}.
 */
@Tag("live")
class BiDiBackendIntegrationTest {

    private static boolean geckodriverAvailable() {
        // opt-in: launching a browser is not something the default suite should do
        return "1".equals(System.getenv("PIJOTA_BROWSER_IT")) && Executables.isAvailable("geckodriver");
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
