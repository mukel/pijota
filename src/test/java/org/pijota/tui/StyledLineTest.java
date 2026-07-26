package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StyledLineTest {

    @Test
    void storesText() {
        var line = StyledLine.of("hello");
        assertEquals("hello", line.text());
    }

    @Test
    void nullTextCoercedToEmpty() {
        var line = StyledLine.of(null);
        assertEquals("", line.text());
    }

    @Test
    void equalityByText() {
        assertEquals(StyledLine.of("a"), StyledLine.of("a"));
        assertNotEquals(StyledLine.of("a"), StyledLine.of("b"));
    }
}
