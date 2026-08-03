package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectorFitDetailTest {

    private static final String MODEL = "[deepseek]  context 976K · input text · price $0.14/$0.28";

    /**
     * Regression: at 100 columns every model row ended in a severed price ("price $0.14/$"). A row must never cut
     * through a value -- it drops the whole fact instead.
     */
    @Test
    void aRowTooNarrowDropsTheLastFactRatherThanCuttingIt() {
        String fit = SelectorRegion.fitDetail(MODEL, 45);

        assertEquals("[deepseek]  context 976K · input text", fit);
        assertFalse(fit.endsWith("$"), "no severed value");
        assertTrue(AnsiWidth.visibleWidth(fit) <= 45);
    }

    @Test
    void itKeepsEveryFactThatFits() {
        assertEquals(MODEL, SelectorRegion.fitDetail(MODEL, 200), "room to spare changes nothing");
        assertEquals(MODEL, SelectorRegion.fitDetail(MODEL, AnsiWidth.visibleWidth(MODEL)), "an exact fit is a fit");
    }

    @Test
    void dropsAsManyFactsAsItTakes() {
        assertEquals("[deepseek]  context 976K", SelectorRegion.fitDetail(MODEL, 30));
    }

    /** Nothing left to drop: cut, and say so, rather than pretend the row is complete. */
    @Test
    void aFirstFactThatCannotFitIsEllipsised() {
        String fit = SelectorRegion.fitDetail(MODEL, 12);
        assertTrue(fit.endsWith("…"), fit);
        assertTrue(AnsiWidth.visibleWidth(fit) <= 12, "still fits: " + AnsiWidth.visibleWidth(fit));
    }

    @Test
    void detailsWithoutSeparatorsAndEmptyOnesAreSafe() {
        assertEquals("", SelectorRegion.fitDetail(null, 20));
        assertEquals("", SelectorRegion.fitDetail("anything", 0));
        assertTrue(SelectorRegion.fitDetail("one long unbroken description", 10).endsWith("…"));
    }
}
