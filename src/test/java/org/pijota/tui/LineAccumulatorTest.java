package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineAccumulatorTest {
    @Test
    void buffersPartialLinesUntilNewlineArrives() {
        var acc = new LineAccumulator();

        assertEquals(List.of(), acc.accept("hel"));
        assertEquals(List.of("hello"), acc.accept("lo\nworld"));
        assertEquals("world", acc.flush().orElseThrow());
        assertTrue(acc.flush().isEmpty());
    }

    @Test
    void emitsMultipleCompleteLinesAndPreservesEmptyLines() {
        var acc = new LineAccumulator();

        assertEquals(List.of("one", "", "two"), acc.accept("one\n\ntwo\n"));
        assertTrue(acc.flush().isEmpty());
    }

    @Test
    void stripsCarriageReturnFromCrLfBoundaries() {
        var acc = new LineAccumulator();

        assertEquals(List.of("one", "two"), acc.accept("one\r\ntwo\r\nthree\r"));
        assertEquals("three", acc.flush().orElseThrow());
    }

    @Test
    void partialViewReturnsBufferedTextWithoutConsumingIt() {
        var acc = new LineAccumulator();

        assertEquals("", acc.partialView());
        acc.accept("hel");
        assertEquals("hel", acc.partialView());
        assertEquals("hel", acc.partialView()); // non-consuming: still buffered
        assertEquals(List.of("hello"), acc.accept("lo\nworld"));
        assertEquals("world", acc.partialView());
        assertEquals("world", acc.flush().orElseThrow());
        assertEquals("", acc.partialView());
    }

    @Test
    void clearDropsBufferedPartialLine() {
        var acc = new LineAccumulator();

        acc.accept("partial");
        acc.clear();

        assertTrue(acc.flush().isEmpty());
    }
}
