package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusOverrideTest {

    @Test
    void emptyByDefault() {
        var s = new StatusOverride();
        assertNull(s.current());
        assertFalse(s.expireIfDue());
    }

    @Test
    void stickyMessageStaysUntilCleared() {
        var s = new StatusOverride();
        s.set("hello");
        assertEquals("hello", s.current());
        assertFalse(s.expireIfDue(), "sticky message never expires on its own");
        assertEquals("hello", s.current());
        s.clear();
        assertNull(s.current());
    }

    @Test
    void timedMessageVisibleBeforeExpiry() {
        var s = new StatusOverride();
        s.setTransient("soon", 10_000);
        assertEquals("soon", s.current());
        assertFalse(s.expireIfDue());
    }

    @Test
    void timedMessageClearsAfterExpiry() throws InterruptedException {
        var s = new StatusOverride();
        s.setTransient("brief", 1);
        Thread.sleep(5);
        assertTrue(s.expireIfDue(), "elapsed timed message should report due once");
        assertNull(s.current());
        assertFalse(s.expireIfDue(), "already-cleared message is not due again");
    }

    @Test
    void currentClearsExpiredAsSideEffect() throws InterruptedException {
        var s = new StatusOverride();
        s.setTransient("brief", 1);
        Thread.sleep(5);
        assertNull(s.current());
        assertFalse(s.expireIfDue());
    }

    @Test
    void setReplacesTimedWithSticky() {
        var s = new StatusOverride();
        s.setTransient("brief", 1);
        s.set("permanent");
        assertEquals("permanent", s.current());
        assertFalse(s.expireIfDue());
    }
}
