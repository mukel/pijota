package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class TuiCopyTest {

    private static final AnsiTheme THEME = AnsiTheme.dark();

    /** A terminal wide enough that nothing is dropped; the narrow cases get their own test. */
    private static String footer(String position, String... hints) {
        return AnsiStripper.strip(TuiCopy.footer(THEME, 200, position, hints));
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

    /**
     * A footer narrower than its hints drops whole hints instead of being clipped mid-word. At 60 columns the
     * selector's "type to search · ↑/↓ navigate · Enter select · Esc cancel" is 63 wide, and what the clip removed was
     * "cancel" -- the one word that says how to get out.
     */
    @Test
    void aFooterTooNarrowDropsWholeHints() {
        String narrow = AnsiStripper.strip(TuiCopy.footer(THEME, 40, "1/2", "type to search", "↑/↓ navigate",
                                        "Enter select", "Esc cancel"));

        assertTrue(AnsiWidth.visibleWidth(narrow) <= 40, "width " + AnsiWidth.visibleWidth(narrow) + ": " + narrow);
        assertFalse(narrow.contains("Enter selec\n"), narrow);
        for (String chip : narrow.substring(narrow.indexOf("1/2") + 3).split(" · "))
            assertFalse(chip.strip().endsWith("…"), "a whole hint was kept or dropped, never sliced: " + narrow);
    }

    @Test
    void fitKeepsEveryChipThatFitsAndDropsFromTheEnd() {
        assertEquals("a · b · c", TuiCopy.fit("a · b · c", 20));
        assertEquals("a · b", TuiCopy.fit("a · b · c", 8));
        assertEquals("a", TuiCopy.fit("a · b · c", 4));
        assertTrue(TuiCopy.fit("averylongsinglechip", 8).endsWith("…"), "nothing left to drop: cut and say so");
        assertEquals("", TuiCopy.fit(null, 10));
        assertEquals("", TuiCopy.fit("x", 0));
    }

    /**
     * Only two separators, and only where they mean what they say. The rule was half-applied and nowhere written down,
     * so the chrome drifted to one, two and three spaces on neighbouring lines: the startup banner separated a title
     * from its metadata with three spaces while the status bar used two, and the spinner was joined to its phase with
     * one. Read together they looked arbitrary rather than meaningful.
     *
     * <p>
     * Two- and three-space joins and a bare {@code ·} are caught; a single space is not. Extending it there flagged
     * {@code fmtCount(...) + " " + pctStr}, where the space sits <em>inside</em> one fact rather than between two, and
     * a check that cries wolf gets deleted rather than obeyed.
     */
    @Test
    void theChromeUsesOnlyTheTwoSanctionedSeparators() throws IOException {
        var offenders = new TreeSet<String>();
        for (String file : List.of("PromptPresenter.java", "TuiCopy.java")) {
            Path path = Path.of("src/main/java/org/pijota/tui", file);
            int line = 0;
            for (String text : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                line++;
                if (text.stripLeading().startsWith("*") || text.stripLeading().startsWith("//")
                                                || text.contains("static final String CHIP")
                                                || text.contains("static final String GROUP"))
                    continue; // the definitions themselves, and the comments explaining them
                if (text.contains("\"  \"") || text.contains("\"   \"") || text.contains("\" · \""))
                    offenders.add(file + ":" + line);
            }
        }

        assertTrue(offenders.isEmpty(), "chrome joins written as literals instead of TuiCopy.CHIP / GROUP: "
                                        + offenders);
    }

    @Test
    void theSeparatorsAreWhatTheyClaimToBe() {
        assertEquals(" · ", TuiCopy.CHIP, "peers");
        assertEquals("  ", TuiCopy.GROUP, "a different kind of thing");
        assertTrue(TuiCopy.GROUP.isBlank() && TuiCopy.GROUP.length() == 2, "nothing wider: that is column alignment");
    }

}
