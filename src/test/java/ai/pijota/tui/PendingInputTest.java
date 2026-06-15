package ai.pijota.tui;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingInputTest {

    @Test
    void inactiveUntilOpened() {
        var p = new PendingInput<String>();
        assertFalse(p.active());
        assertFalse(p.fulfill("x"), "fulfilling with no request is a no-op");
        assertFalse(p.cancel(), "cancelling with no request is a no-op");
    }

    @Test
    void fulfillCompletesTheFutureAndClearsActive() {
        var p = new PendingInput<String>();
        var future = p.open();
        assertTrue(p.active());

        assertTrue(p.fulfill("answer"));
        assertEquals("answer", future.join()); // settled asynchronously off the caller thread
        assertFalse(p.active(), "no longer active once fulfilled");
        assertFalse(p.fulfill("again"), "second fulfill is a no-op");
    }

    @Test
    void cancelCompletesExceptionallyAndClearsActive() {
        var p = new PendingInput<String>();
        var future = p.open();

        assertTrue(p.cancel());
        assertThrows(CancellationException.class, future::join);
        assertFalse(p.active());
        assertFalse(p.cancel(), "second cancel is a no-op");
    }

    @Test
    void openReplacesAnyPriorRequest() {
        var p = new PendingInput<String>();
        var first = p.open();
        var second = p.open();

        assertTrue(p.fulfill("v"));
        assertEquals("v", second.join());
        assertFalse(first.isDone(), "the superseded request is left untouched");
    }
}
