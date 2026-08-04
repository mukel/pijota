package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TurnActivityTest {

    @Test
    void startsIdle() {
        var a = new TurnActivity();
        assertFalse(a.isBusy());
        assertNull(a.phase());
    }

    @Test
    void beginMarksBusyWithPhaseAndEndClearsIt() {
        var a = new TurnActivity();
        long token = a.begin("waiting for model");
        assertTrue(a.isBusy());
        assertEquals("waiting for model", a.phase());

        assertTrue(a.end(token), "the matching token ends the turn");
        assertFalse(a.isBusy());
    }

    @Test
    void setPhaseUpdatesLabelDuringTurn() {
        var a = new TurnActivity();
        a.begin("waiting for model");
        a.setPhase("tool edit");
        assertEquals("tool edit", a.phase());
    }

    @Test
    void staleEndFromSupersededTurnIsIgnored() {
        var a = new TurnActivity();
        long first = a.begin("first");
        long second = a.begin("second"); // first turn superseded without ending

        assertFalse(a.end(first), "a token from the superseded turn must not end the current turn");
        assertTrue(a.isBusy(), "still busy with the second turn");

        assertTrue(a.end(second), "the current turn's token ends it");
        assertFalse(a.isBusy());
    }

    @Test
    void endIsIdempotentForAlreadyEndedToken() {
        var a = new TurnActivity();
        long token = a.begin("x");
        assertTrue(a.end(token));
        assertFalse(a.end(token), "ending twice with the same token is a no-op");
    }

    /**
     * The cue exists to prove the model is still producing, so the number has to move. fmtCount rounds to whole
     * thousands, which on a slow write leaves it reading the same for a thousand characters: half a minute of a counter
     * that says nothing is happening, which is the bug it was added to fix.
     */
    @Test
    void theProgressCounterChangesWhileTheToolCallGrows() {
        String a = TuiCopy.toolProgress("write", 12_300);
        String b = TuiCopy.toolProgress("write", 12_400);

        assertNotEquals(a, b, "a counter that repeats itself for 1000 characters reads as frozen");
        assertTrue(a.startsWith("write "), a);
        assertTrue(a.endsWith(" chars"), "the unit matters: the line already carries a token count " + a);
    }

    /**
     * CHIP joins facts of the same kind and GROUP opens a different kind. The tool and its size are one fact; a chip
     * between them put the tool, the size and the elapsed seconds on the line as three peers.
     */
    @Test
    void theToolAndItsSizeReadAsOneField() {
        assertFalse(TuiCopy.toolProgress("write", 12_300).contains(TuiCopy.CHIP),
                                        "a separator inside a field flattens it against its neighbours");
    }

}
