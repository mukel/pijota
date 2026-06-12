package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

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
}
