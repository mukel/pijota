package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import org.commonmark.ext.gfm.tables.TableCell;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownTableRendererTest {
    @Test
    void rendersValidGfmTable() {
        var table = "| Col1 | Col2 |\n|---|---|\n|a|b|\n|c|d|";
        var rendered = MarkdownTableRenderer.renderIfTable(table, AnsiTheme.dark(), 80);

        assertTrue(rendered.isPresent());
        var clean = rendered.get().stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(clean.stream().anyMatch(l -> l.contains("Col1")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("a")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("d")));
    }

    @Test
    void returnsEmptyForNonTableMarkdown() {
        var rendered = MarkdownTableRenderer.renderIfTable("not a table", AnsiTheme.dark(), 80);

        assertTrue(rendered.isEmpty());
    }

    @Test
    void returnsEmptyForTextWithSinglePipeLine() {
        var rendered = MarkdownTableRenderer.renderIfTable("| single line", AnsiTheme.dark(), 80);

        assertTrue(rendered.isEmpty());
    }

    @Test
    void separatorRowIsPresentBetweenHeaderAndBody() {
        var table = "| H |\n|---|\n| v |";
        var rendered = MarkdownTableRenderer.renderIfTable(table, AnsiTheme.dark(), 80);

        assertTrue(rendered.isPresent());
        assertTrue(rendered.get().size() >= 4, "should contain blank lines and separator row around header and body");
    }

    @Test
    void couldBeTableDetectsPipesAndNewlines() {
        assertTrue(MarkdownTableRenderer.couldBeTable("| a | b |\n| c | d |"));
        assertFalse(MarkdownTableRenderer.couldBeTable("no pipes here"));
        assertFalse(MarkdownTableRenderer.couldBeTable("| single line"));
    }

    @Test
    void sumAddsAllElements() {
        assertEquals(0, MarkdownTableRenderer.sum(new int[0]));
        assertEquals(10, MarkdownTableRenderer.sum(new int[] {4, 6}));
        assertEquals(15, MarkdownTableRenderer.sum(new int[] {1, 2, 3, 4, 5}));
    }

    @Test
    void widestReturnsIndexOfLargest() {
        assertEquals(0, MarkdownTableRenderer.widest(new int[] {5}));
        assertEquals(2, MarkdownTableRenderer.widest(new int[] {1, 3, 7, 2}));
        assertEquals(0, MarkdownTableRenderer.widest(new int[] {5, 5}));
    }

    @Test
    void spacesGeneratesRepeatedSpaces() {
        assertEquals("", MarkdownTableRenderer.spaces(0));
        assertEquals("   ", MarkdownTableRenderer.spaces(3));
        assertEquals(" ".repeat(10), MarkdownTableRenderer.spaces(10));
    }

    @Test
    void alignLeftPadsRight() {
        var result = MarkdownTableRenderer.align("ab", 6, TableCell.Alignment.LEFT);
        assertEquals("ab    ", AnsiStripper.strip(result));
    }

    @Test
    void alignRightPadsLeft() {
        var result = MarkdownTableRenderer.align("ab", 6, TableCell.Alignment.RIGHT);
        assertEquals("    ab", AnsiStripper.strip(result));
    }

    @Test
    void alignCenterPadsBoth() {
        var result = MarkdownTableRenderer.align("ab", 6, TableCell.Alignment.CENTER);
        String stripped = AnsiStripper.strip(result);
        assertTrue(stripped.startsWith(" "));
        assertTrue(stripped.endsWith(" "));
        assertEquals(6, stripped.length());
    }

    @Test
    void alignReturnsTextWhenNoPadNeeded() {
        assertEquals("abcdef", AnsiStripper.strip(MarkdownTableRenderer.align("abcdef", 6, TableCell.Alignment.LEFT)));
        assertEquals("abcdef", AnsiStripper.strip(MarkdownTableRenderer.align("abcdef", 4, TableCell.Alignment.LEFT)));
    }
}
