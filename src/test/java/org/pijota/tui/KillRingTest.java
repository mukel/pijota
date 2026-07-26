package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KillRingTest {

    @Test
    void isEmptyOnNewRing() {
        assertTrue(new KillRing().isEmpty());
    }

    @Test
    void notEmptyAfterAdd() {
        var ring = new KillRing();
        ring.add("killed text");
        assertFalse(ring.isEmpty());
    }

    @Test
    void addNullIsIgnored() {
        var ring = new KillRing();
        ring.add(null);
        assertTrue(ring.isEmpty());
    }

    @Test
    void addEmptyStringIsIgnored() {
        var ring = new KillRing();
        ring.add("");
        assertTrue(ring.isEmpty());
    }

    @Test
    void yankReturnsNullOnEmptyRing() {
        assertNull(new KillRing().yank());
    }

    @Test
    void yankReturnsMostRecentKill() {
        var ring = new KillRing();
        ring.add("first");
        ring.add("second");
        assertEquals("second", ring.yank());
    }

    @Test
    void yankOnSingleEntry() {
        var ring = new KillRing();
        ring.add("only");
        assertEquals("only", ring.yank());
    }

    @Test
    void yankPopReturnsNullBeforeYank() {
        var ring = new KillRing();
        ring.add("text");
        assertNull(ring.yankPop());
    }

    @Test
    void yankPopReturnsNullOnEmptyRing() {
        assertNull(new KillRing().yankPop());
    }

    @Test
    void yankPopCyclesToOlderKill() {
        var ring = new KillRing();
        ring.add("first");
        ring.add("second");
        ring.add("third");
        assertEquals("third", ring.yank());
        assertEquals("second", ring.yankPop());
        assertEquals("first", ring.yankPop());
    }

    @Test
    void yankPopWrapsAround() {
        var ring = new KillRing();
        ring.add("first");
        ring.add("second");
        ring.add("third");
        assertEquals("third", ring.yank());
        assertEquals("second", ring.yankPop());
        assertEquals("first", ring.yankPop());
        assertEquals("third", ring.yankPop());
    }

    @Test
    void yankResetsCursorToMostRecent() {
        var ring = new KillRing();
        ring.add("first");
        ring.add("second");
        ring.yank();
        ring.yankPop();
        assertEquals("second", ring.yank());
        assertEquals("first", ring.yankPop());
    }

    @Test
    void addResetsCursor() {
        var ring = new KillRing();
        ring.add("first");
        ring.add("second");
        ring.yank();
        ring.yankPop();
        ring.add("third");
        assertEquals("third", ring.yank());
        assertEquals("second", ring.yankPop());
        assertEquals("first", ring.yankPop());
    }

    @Test
    void yankPopAfterSingleYank() {
        var ring = new KillRing();
        ring.add("only");
        assertEquals("only", ring.yank());
        assertEquals("only", ring.yankPop());
    }

    @Test
    void manyYankPopsCycleCorrectly() {
        var ring = new KillRing();
        ring.add("a");
        ring.add("b");
        ring.yank();
        for (int i = 0; i < 10; i++) {
            var val = ring.yankPop();
            assertNotNull(val);
        }
    }

    @Test
    void maxEntriesEvictsOldest() {
        var ring = new KillRing();
        for (int i = 0; i < 35; i++) {
            ring.add("entry-" + i);
        }
        assertEquals("entry-34", ring.yank());
        assertEquals("entry-33", ring.yankPop());
        assertEquals("entry-32", ring.yankPop());
        for (int i = 0; i < 28; i++) {
            ring.yankPop();
        }
        assertEquals("entry-3", ring.yankPop());
        assertEquals("entry-34", ring.yankPop());
    }

    @Test
    void exactMaxEntriesRetained() {
        var ring = new KillRing();
        for (int i = 0; i < 32; i++) {
            ring.add("entry-" + i);
        }
        assertEquals("entry-31", ring.yank());
        for (int i = 0; i < 30; i++) {
            ring.yankPop();
        }
        assertEquals("entry-0", ring.yankPop());
    }

    @Test
    void addAfterFullRing() {
        var ring = new KillRing();
        for (int i = 0; i < 32; i++) {
            ring.add("entry-" + i);
        }
        ring.add("new-entry");
        assertEquals("new-entry", ring.yank());
        assertEquals("entry-31", ring.yankPop());
        for (int i = 0; i < 29; i++) {
            ring.yankPop();
        }
        assertEquals("entry-1", ring.yankPop());
    }

    @Test
    void interleavedAddAndYank() {
        var ring = new KillRing();
        ring.add("a");
        ring.add("b");
        assertEquals("b", ring.yank());
        ring.add("c");
        assertEquals("c", ring.yank());
        assertEquals("b", ring.yankPop());
        assertEquals("a", ring.yankPop());
        assertEquals("c", ring.yankPop());
    }
}
