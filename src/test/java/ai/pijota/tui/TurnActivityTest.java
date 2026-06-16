package ai.pijota.tui;

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
}
