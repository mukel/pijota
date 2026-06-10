package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownTableRendererTest {
    @Test
    void rendersSimpleMarkdownTableWithAlignment() {
        var result = MarkdownTableRenderer.renderIfTable(
            "| A   | B   |\n| --- | --- |\n| x   | y   |",
            AnsiTheme.dark(), 80);

        assertTrue(result.isPresent());
        var lines = result.get();
        assertEquals(5, lines.size());
        assertTrue(lines.get(1).text().contains("A"));
        assertTrue(lines.get(1).text().contains("B"));
        assertTrue(lines.get(2).text().contains("─"));
        assertTrue(lines.get(3).text().contains("x"));
        assertTrue(lines.get(3).text().contains("y"));
    }

    @Test
    void headersUseBoldSlot() {
        var result = MarkdownTableRenderer.renderIfTable(
            "| Name |\n| ---- |\n| val  |",
            AnsiTheme.dark(), 80);

        var headerLine = result.orElseThrow().get(1).text();
        assertTrue(headerLine.contains("\u001B[1m"), "header should use bold style");
        assertEquals("Name", AnsiStripper.strip(headerLine).trim());
    }

    @Test
    void returnsEmptyWhenInputIsNotATable() {
        assertEquals(Optional.empty(), MarkdownTableRenderer.renderIfTable(
            "plain text", AnsiTheme.dark(), 80));
        assertEquals(Optional.empty(), MarkdownTableRenderer.renderIfTable(
            "| single pipe |", AnsiTheme.dark(), 80));
    }
}
