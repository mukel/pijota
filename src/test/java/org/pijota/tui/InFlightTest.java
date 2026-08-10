package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The in-flight state on its own. It used to be two fields and a hand-rolled max scan inside AppendOnlyTui, where the
 * only way to reach any of this was to drive a terminal and watch the line above the prompt.
 */
class InFlightTest {

    @Test
    void theNewestToolWinsWhateverOrderTheyArrivedIn() {
        var inFlight = new InFlight();
        inFlight.toolStarted("a", "read", "src/Main.java", () -> "{}", 1_000);
        inFlight.toolStarted("b", "bash", "make test", () -> "{}", 3_000);
        inFlight.toolStarted("c", "grep", "TODO", () -> "{}", 2_000);

        assertEquals("bash", inFlight.newestTool().name(), "the most recently started, not the last entered");
    }

    /**
     * A tool call fired while a {@code !} command is still running must not leave the tail describing the older of the
     * two. Both timestamps are exposed for exactly that comparison, so the rule is checkable here.
     */
    @Test
    void aShellCommandAndAToolCallAreComparableByWhenTheyStarted() {
        var inFlight = new InFlight();
        inFlight.shellStarted("sleep 30", 1_000);
        inFlight.toolStarted("t1", "bash", "make", () -> "{}", 2_000);

        assertTrue(inFlight.newestTool().startedAtMs() > inFlight.shell().startedAtMs(), "the tool call is newer");
    }

    @Test
    void aFinishedCallHandsBackWhatWasKnownAboutIt() {
        var inFlight = new InFlight();
        inFlight.toolStarted("t1", "edit", "README.md", () -> "{\"path\":\"README.md\"}", 5_000);

        var done = inFlight.toolFinished("t1");
        assertNotNull(done);
        assertEquals("edit", done.name());
        assertEquals("{\"path\":\"README.md\"}", done.args().get(), "the arguments are re-read when the result lands");
        assertNull(inFlight.newestTool(), "and it is no longer running");
        assertNull(inFlight.toolFinished("t1"), "a second result for the same call finds nothing");
        assertNull(inFlight.toolFinished(null), "a result with no call id belongs to nobody");
    }

    /**
     * Abandoned calls are committed oldest-first, which is the order their headers were printed in; a newest-first list
     * would report an interrupted turn backwards.
     */
    @Test
    void takingEveryCallReturnsThemOldestFirstAndLeavesNothingBehind() {
        var inFlight = new InFlight();
        inFlight.toolStarted("b", "bash", "", () -> "{}", 3_000);
        inFlight.toolStarted("a", "read", "", () -> "{}", 1_000);
        inFlight.toolStarted("c", "grep", "", () -> "{}", 2_000);

        var taken = inFlight.takeAll();
        assertEquals(java.util.List.of("a", "c", "b"), taken.stream().map(java.util.Map.Entry::getKey).toList());
        assertTrue(inFlight.noTools(), "taking them clears them: they are being reported as interrupted");
    }

    @Test
    void nothingRunningIsTheStartingStateAndTheStateAfterAClear() {
        var inFlight = new InFlight();
        assertTrue(inFlight.noTools());
        assertNull(inFlight.newestTool());
        assertNull(inFlight.shell());

        inFlight.toolStarted("t1", "bash", "", () -> "{}", 1_000);
        inFlight.clearTools();
        assertTrue(inFlight.noTools(), "a turn that was interrupted leaves no tools behind for the next one");
    }

    @Test
    void aShellCommandThatEndsLeavesNoneRunning() {
        var inFlight = new InFlight();
        inFlight.shellStarted("sleep 30", 1_000);
        assertNotNull(inFlight.shell());
        inFlight.shellStarted(null, 2_000);
        assertNull(inFlight.shell(), "null is how the interface says the ! command finished");
    }
}
