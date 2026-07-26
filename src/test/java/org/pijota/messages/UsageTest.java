package org.pijota.messages;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UsageTest {

    @Test
    void storesTokens() {
        var usage = new Usage(100, 50);
        assertEquals(100, usage.inputTokens());
        assertEquals(50, usage.outputTokens());
    }

    @Test
    void emptyHasZeroTokens() {
        assertEquals(0, Usage.EMPTY.inputTokens());
        assertEquals(0, Usage.EMPTY.outputTokens());
    }

    @Test
    void equalityByFields() {
        assertEquals(new Usage(1, 2), new Usage(1, 2));
        assertNotEquals(new Usage(1, 2), new Usage(2, 1));
    }
}
