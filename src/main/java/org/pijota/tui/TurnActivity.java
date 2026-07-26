package org.pijota.tui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks whether an agent turn is running and what phase it is in. {@link #begin} returns a generation token that
 * {@link #end} must present to finish the turn; a token from a superseded turn is ignored, so a late {@code end} from
 * an interrupted turn can never clear the activity of the turn that replaced it. Thread-safe; self-contained and
 * unit-testable without a terminal.
 */
final class TurnActivity {
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0);
    private volatile String phase;
    private volatile long startMillis;

    /**
     * Marks a turn as running with an initial phase label; returns the token its matching {@link #end} must present.
     */
    long begin(String phase) {
        busy.set(true);
        this.phase = phase;
        this.startMillis = System.currentTimeMillis();
        return generation.incrementAndGet();
    }

    /** Milliseconds since the current turn began, or 0 when no turn is running. */
    long elapsedMillis() {
        return busy.get() ? System.currentTimeMillis() - startMillis : 0;
    }

    /** Ends the turn iff {@code token} is the current generation; returns true if this call actually ended it. */
    boolean end(long token) {
        if (!generation.compareAndSet(token, 0))
            return false;
        busy.set(false);
        return true;
    }

    boolean isBusy() {
        return busy.get();
    }

    /** The current phase label (e.g. "waiting for model", "tool edit"), or {@code null} when none is set. */
    String phase() {
        return phase;
    }

    void setPhase(String phase) {
        this.phase = phase;
    }
}
