package org.pijota.tui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks follow-up prompts the user submits while the agent is busy. The {@code pending} list backs the prompt's
 * "pending messages" preview; the {@code queued} counter backs the "queued N" busy-bar hint. They clear at different
 * moments (see {@link #reset()} vs {@link #clearPending()}), which is why this is a small explicit type rather than two
 * loose fields.
 */
final class FollowUpQueue {

    private final List<String> pending = new CopyOnWriteArrayList<>();
    private final AtomicInteger queued = new AtomicInteger(0);

    /** Adds a follow-up (agent is busy); returns the new queued count. */
    int add(String payload) {
        pending.add(payload);
        return queued.incrementAndGet();
    }

    /** Resets counter and pending list, used when a fresh prompt run starts. */
    void reset() {
        queued.set(0);
        pending.clear();
    }

    /** Clears the pending preview but keeps the queued counter, used at the start of each turn. */
    void clearPending() {
        pending.clear();
    }

    int queuedCount() {
        return queued.get();
    }

    List<String> pending() {
        return List.copyOf(pending);
    }

    /** Removes and returns the newest pending follow-up for editing, or {@code null} when the queue is empty. */
    String popNewest() {
        int size = pending.size();
        if (size == 0)
            return null;
        String value = pending.remove(size - 1);
        queued.updateAndGet(count -> Math.max(0, count - 1));
        return value;
    }

    List<String> drainPending() {
        var out = List.copyOf(pending);
        pending.clear();
        queued.set(0);
        return out;
    }
}
