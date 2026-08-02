package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportTest {

    private static final AnsiTheme THEME = AnsiTheme.dark();

    /** Rendered content, without the leading blank line of air (covered on its own below). */
    private static List<String> plain(Report report) {
        var lines = report.render().stream().map(AnsiStripper::strip).toList();
        return lines.isEmpty() || !lines.get(0).isEmpty() ? lines : lines.subList(1, lines.size());
    }

    @Test
    void aPanelClaimsItsOwnLineOfAir() {
        // Every block in the interface opens with a blank so it does not butt against what came before; consecutive
        // blanks collapse downstream, so a panel does not have to know what preceded it.
        assertEquals("", new Report(THEME::styled, "usage").row("input", "0").render().get(0));
    }

    /** The defect this exists to prevent: a label wider than its neighbours used to push its own row out of line. */
    @Test
    void everyValueStartsInTheSameColumnRegardlessOfLabelWidth() {
        var lines = plain(new Report(THEME::styled, "context").row("auto-compact", "on").row("system", "1 msg")
                                        .row("tool", "0 msgs"));

        var valueColumns = lines.subList(1, lines.size()).stream().map(l -> l.indexOf(l.strip().split(" {2,}")[1]))
                                        .distinct().toList();
        assertEquals(1, valueColumns.size(), "values must share one column: " + lines);
    }

    @Test
    void theLabelColumnIsSizedFromTheWidestLabelNotAFixedGuess() {
        var narrow = plain(new Report(THEME::styled, "t").row("a", "1").row("bb", "2"));
        var wide = plain(new Report(THEME::styled, "t").row("a", "1").row("a-very-long-label", "2"));
        assertTrue(wide.get(1).indexOf('1') > narrow.get(1).indexOf('1'),
                                        "a wider label must move the whole column, not just its own row: " + wide);
    }

    @Test
    void headerChipsUseTheSameSeparatorAsTheRestOfTheInterface() {
        var lines = plain(new Report(THEME::styled, "context").chip("1 msg").chip("3.9K/1.0M (0%)"));
        assertEquals("context  1 msg · 3.9K/1.0M (0%)", lines.get(0),
                                        "one header grammar: title, then · -joined chips");
    }

    @Test
    void aHeaderWithoutChipsIsJustTheTitle() {
        assertEquals("skills", plain(new Report(THEME::styled, "skills")).get(0));
    }

    @Test
    void blankChipsAreDroppedRatherThanLeavingADanglingSeparator() {
        var lines = plain(new Report(THEME::styled, "usage").chip("this session").chip("").chip(null));
        assertEquals("usage  this session", lines.get(0), "no trailing separator: " + lines.get(0));
    }

    @Test
    void aNoteIsIndentedWithTheRowsButCarriesNoLabelColumn() {
        var lines = plain(new Report(THEME::styled, "usage").row("input", "10 tokens")
                                        .note("some usage on unpriced models"));
        assertEquals("  some usage on unpriced models", lines.get(2));
    }

    @Test
    void theTitleAndLabelsAreStyledDifferentlyFromValues() {
        var raw = new Report(THEME::styled, "context").row("system", "1 msg").render();
        assertTrue(raw.get(1).contains("\033["), "the title carries a style");
        assertTrue(raw.get(2).contains("\033["), "the row carries a style");
    }

    @Test
    void anEmptyReportIsJustItsHeaderNotABlankBlock() {
        assertEquals(1, plain(new Report(THEME::styled, "skills")).size());
    }
}
