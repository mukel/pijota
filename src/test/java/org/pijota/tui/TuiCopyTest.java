package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiCopyTest {

    private static final AnsiTheme THEME = AnsiTheme.dark();

    private static String footer(String position, String... hints) {
        return AnsiStripper.strip(TuiCopy.footer(THEME, position, hints));
    }

    @Test
    void selectorHintIsNotEmpty() {
        assertNotNull(TuiCopy.SELECTOR_HINT);
        assertFalse(TuiCopy.SELECTOR_HINT.isBlank());
    }

    /**
     * The selector, the completions popup and the log viewer each had their own footer grammar -- two joined the hints
     * with {@code "  · "} and one with plain {@code "  "} -- so the same line read differently depending on which
     * overlay happened to be open.
     */
    @Test
    void positionAndHintsAreSeparatedByTwoSpacesNotABullet() {
        assertEquals("  5/10  type to search · Esc cancel", footer("5/10", "type to search", "Esc cancel"));
    }

    @Test
    void hintsAreJoinedWithTheSameChipSeparatorAsAReportHeader() {
        String reportHeader = AnsiStripper.strip(new Report(THEME::styled, "x").chip("a").chip("b").render().get(1));
        assertTrue(reportHeader.contains(" · "), reportHeader);
        assertTrue(footer("1/2", "a", "b").contains(" · "), "headers and footers follow one rule");
    }

    @Test
    void aFooterWithNoHintsIsJustThePosition() {
        assertEquals("  3/3", footer("3/3"));
    }

    @Test
    void aFooterWithNoPositionStillRendersItsHints() {
        // The empty selector has nothing to count but still has to say how to get out.
        assertEquals("  Esc cancel", footer("", "Esc cancel"));
    }

    @Test
    void blankHintsAreDroppedRatherThanLeavingADanglingSeparator() {
        assertEquals("  1/2  only", footer("1/2", "only", "", null, "   "));
    }

    /** Shortcut notation is OS-mapped in one place, so a footer cannot show Ctrl+X on a Mac by forgetting to map it. */
    @Test
    void shortcutNotationGoesThroughTheOsMapping() {
        assertEquals(Keys.label("Ctrl+O expand"), footer("", "Ctrl+O expand").strip());
    }
}
