package org.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FollowUpQueueTest {

    @Test
    void addReturnsRunningCountAndTracksPending() {
        var q = new FollowUpQueue();
        assertEquals(1, q.add("a"));
        assertEquals(2, q.add("b"));
        assertEquals(2, q.queuedCount());
        assertEquals(List.of("a", "b"), q.pending());
    }

    @Test
    void resetClearsBothCounterAndPending() {
        var q = new FollowUpQueue();
        q.add("a");
        q.add("b");
        q.reset();
        assertEquals(0, q.queuedCount());
        assertTrue(q.pending().isEmpty());
    }

    @Test
    void clearPendingKeepsCounter() {
        var q = new FollowUpQueue();
        q.add("a");
        q.add("b");
        q.clearPending();
        assertTrue(q.pending().isEmpty(), "preview cleared");
        assertEquals(2, q.queuedCount(), "counter retained across the turn");
    }

    @Test
    void pendingSnapshotIsIndependentCopy() {
        var q = new FollowUpQueue();
        q.add("a");
        var snap = q.pending();
        q.add("b");
        assertEquals(List.of("a"), snap, "earlier snapshot must not see later additions");
    }
}
