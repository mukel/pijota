package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the multi-line block accumulator used to buffer tables/blockquotes until they can parse with full context. */
class LineBufferTest {

    @Test
    void startsInactive() {
        assertFalse(new LineBuffer().active());
    }

    @Test
    void addedLinesJoinWithNewlineAndFlushAsOneElement() {
        var buf = new LineBuffer();
        buf.add("| a | b |");
        buf.add("|---|---|");
        buf.add("| 1 | 2 |");
        assertTrue(buf.active());

        var out = new ArrayList<String>();
        buf.flushInto(out);
        assertEquals(1, out.size());
        assertEquals("| a | b |\n|---|---|\n| 1 | 2 |", out.get(0));
        assertFalse(buf.active(), "flush resets the buffer");
    }

    @Test
    void flushOnEmptyEmitsNothing() {
        var out = new ArrayList<String>();
        new LineBuffer().flushInto(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void clearDiscardsWithoutEmitting() {
        var buf = new LineBuffer();
        buf.add("dropped");
        buf.clear();
        assertFalse(buf.active());
        var out = new ArrayList<String>();
        buf.flushInto(out);
        assertTrue(out.isEmpty());
    }
}
