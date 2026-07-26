package org.pijota.browser;

import org.pijota.util.Platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        proc.start(javaCommand("boom.reason.MissingMain"));
        try {
            for (int i = 0; i < 300 && proc.aliveOrAbsent(); i++)
                Thread.sleep(10);

            assertFalse(proc.aliveOrAbsent(), "process should have exited");
            String diagnostic = proc.exitDiagnostic();
            assertTrue(diagnostic.contains("code"), diagnostic);
            assertTrue(diagnostic.contains("boom.reason.MissingMain"), diagnostic);
        } finally {
            proc.kill();
        }
    }

    @Test
    void exitDiagnosticIsEmptyWhileTheProcessRuns() throws Exception {
        var proc = new LaunchedProcess();
        proc.start(javaCommand("-cp", System.getProperty("java.class.path"), Sleeper.class.getName()));
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

    private static List<String> javaCommand(String... args) {
        var command = new ArrayList<String>();
        command.add(Platform.javaBinary().toString());
        command.addAll(List.of(args));
        return command;
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(5_000);
        }
    }
}
