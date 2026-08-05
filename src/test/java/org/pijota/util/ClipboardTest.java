package org.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardTest {

    @Test
    void nullTextReturnsFalse() {
        assertFalse(Clipboard.copy(null));
    }

    @Test
    void copyNeverThrowsRegardlessOfEnvironment() {
        // Success depends on whether a clipboard tool is installed, but the contract is that it degrades to a boolean
        // and never propagates an exception (or an Error, as the old AWT path could in a native image).
        assertDoesNotThrow(() -> Clipboard.copy("pijota clipboard test"));
    }

    /** A command that read the text and exited cleanly took it. */
    @Test
    void aCommandThatExitsCleanlyCountsAsSuccess() throws Exception {
        assertTrue(Clipboard.settled(new ProcessBuilder("true").start()));
    }

    /** One that exited complaining did not, however quickly it did so. */
    @Test
    void aCommandThatExitsNonZeroCountsAsFailure() throws Exception {
        assertFalse(Clipboard.settled(new ProcessBuilder("false").start()));
    }

    /**
     * A command still running at the deadline is holding the selection, which is what xclip does when it is built not
     * to fork. It is indistinguishable from a hang, so it is read as success -- but it must be read quickly.
     *
     * <p>
     * The bound is the point: {@code /copy} runs on the thread drawing the interface, and this used to wait five
     * seconds before answering, so the terminal sat still for five seconds and then reported a success that had not
     * happened. Two seconds is loose enough not to flake on a loaded machine and still an order below the old wait.
     */
    @Test
    void aCommandStillRunningIsAnsweredQuicklyRatherThanWaitedOut() throws Exception {
        var process = new ProcessBuilder("sleep", "30").start();
        try {
            long start = System.nanoTime();
            boolean result = Clipboard.settled(process);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(result, "a command holding the selection is a successful copy");
            assertTrue(elapsedMs < 2000, () -> "answering took " + elapsedMs + "ms, which the interface waits out");
        } finally {
            process.destroyForcibly();
        }
    }
}
