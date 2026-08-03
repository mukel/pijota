package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectorNarrowRowTest {

    private static final String MODEL = "[deepseek]  context 976K · input text · price $0.14/$0.28";

    private record StubItem(String titleText, String detailText) implements SelectorItem {
        @Override
        public String searchText() {
            return titleText;
        }

        @Override
        public boolean selected() {
            return false;
        }
    }

    private static Selector<StubItem> selectorOf(StubItem... items) {
        var selector = new Selector<>(List.of(items));
        selector.activate("");
        return selector;
    }

    private static PromptSnapshot snapshot() {
        return new PromptSnapshot(List.of(""), 0, 0, List.of(), -1);
    }

    /**
     * Regression: at 100 columns every model row ended in a severed price ("price $0.14/$"). A row must never cut
     * through a value -- it drops the whole fact instead.
     */
    @Test
    void aRowTooNarrowDropsTheLastFactRatherThanCuttingIt() {
        String fit = TuiCopy.fit(MODEL, 45);

        assertEquals("[deepseek]  context 976K · input text", fit);
        assertFalse(fit.endsWith("$"), "no severed value");
        assertTrue(AnsiWidth.visibleWidth(fit) <= 45);
    }

    @Test
    void itKeepsEveryFactThatFits() {
        assertEquals(MODEL, TuiCopy.fit(MODEL, 200), "room to spare changes nothing");
        assertEquals(MODEL, TuiCopy.fit(MODEL, AnsiWidth.visibleWidth(MODEL)), "an exact fit is a fit");
    }

    @Test
    void dropsAsManyFactsAsItTakes() {
        assertEquals("[deepseek]  context 976K", TuiCopy.fit(MODEL, 30));
    }

    /** Nothing left to drop: cut, and say so, rather than pretend the row is complete. */
    @Test
    void aFirstFactThatCannotFitIsEllipsised() {
        String fit = TuiCopy.fit(MODEL, 12);
        assertTrue(fit.endsWith("…"), fit);
        assertTrue(AnsiWidth.visibleWidth(fit) <= 12, "still fits: " + AnsiWidth.visibleWidth(fit));
    }

    @Test
    void detailsWithoutSeparatorsAndEmptyOnesAreSafe() {
        assertEquals("", TuiCopy.fit(null, 20));
        assertEquals("", TuiCopy.fit("anything", 0));
        assertTrue(TuiCopy.fit("one long unbroken description", 10).endsWith("…"));
    }

    /**
     * A picker has to show something to pick by. Sized purely by its widest title, the model column took 41 of 60
     * columns for {@code accounts/fireworks/models/deepseek-v4-pro} and every row rendered as {@code [deepseek]  …}.
     */
    @Test
    void aNarrowSelectorGivesUpTitleWidthRatherThanTheDetail() {
        var wide = new StubItem("accounts/fireworks/models/deepseek-v4-pro", MODEL);
        var short_ = new StubItem("deepseek-v4-flash", MODEL);
        var region = new SelectorRegion<>(selectorOf(wide, short_));

        var rows = region.compose(60, AnsiTheme.plain(), snapshot()).rows();

        String first = rows.stream().filter(r -> r.contains("deepseek-v4-pro")).findFirst().orElseThrow();
        assertTrue(first.contains("…"), "the long id gives way: " + first);
        assertTrue(first.contains("context"), "and the detail survives: " + first);
        for (String row : rows)
            assertTrue(AnsiWidth.visibleWidth(row) <= 60, "row overflows: " + row);
    }

}
