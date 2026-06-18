package ai.pijota.browser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link LaunchedProcess#exitDiagnostic()} — the mechanism that lets a "browser/driver did not come up" timeout
 * name the real cause (exit code + captured stderr tail) instead of just the symptom. Uses a trivial shell process, no
 * browser.
 */
class LaunchedProcessTest {

    @Test
    void exitDiagnosticReportsExitCodeAndStderrTail() throws Exception {
        var proc = new LaunchedProcess();
        proc.start(List.of("/bin/sh", "-c", "echo boom-reason >&2; exit 3"));
        try {
            for (int i = 0; i < 300 && proc.aliveOrAbsent(); i++)
                Thread.sleep(10);

            assertFalse(proc.aliveOrAbsent(), "process should have exited");
            String diagnostic = proc.exitDiagnostic();
            assertTrue(diagnostic.contains("code 3"), diagnostic);
            assertTrue(diagnostic.contains("boom-reason"), diagnostic);
        } finally {
            proc.kill();
        }
    }

    @Test
    void exitDiagnosticIsEmptyWhileTheProcessRuns() throws Exception {
        var proc = new LaunchedProcess();
        proc.start(List.of("/bin/sh", "-c", "sleep 5"));
        try {
            assertTrue(proc.running());
            assertEquals("", proc.exitDiagnostic(), "a running process has no exit diagnostic yet");
        } finally {
            proc.kill();
        }
    }

    @Test
    void exitDiagnosticIsEmptyWhenNothingWasLaunched() {
        assertEquals("", new LaunchedProcess().exitDiagnostic());
    }
}
