package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownTableRendererTest {
    @Test
    void rendersTableWithHeadersAndData() {
        var rendered = MarkdownTableRenderer.renderIfTable(
            "| Name | Age |\n|------|-----|\n| Alice | 30 |",
            AnsiTheme.dark(), 40);

        assertTrue(rendered.isPresent());
        var lines = rendered.get();
        assertTrue(lines.size() >= 4);
        assertTrue(AnsiStripper.strip(lines.get(1).text()).contains("Alice"));
        assertTrue(AnsiStripper.strip(lines.get(1).text()).contains("30"));
    }

    @Test
    void returnsEmptyForNonTableInput() {
        assertTrue(MarkdownTableRenderer.renderIfTable(
            "plain text line", AnsiTheme.dark(), 40).isEmpty());

        assertTrue(MarkdownTableRenderer.renderIfTable(
            "|orphan pipe no newline", AnsiTheme.dark(), 40).isEmpty());
    }

    @Test
    void tableSeparatorUsesThemeHrSlot() {
        var rendered = MarkdownTableRenderer.renderIfTable(
            "| A | B |\n|---|---|", AnsiTheme.dark(), 30);

        assertTrue(rendered.isPresent());
        var separatorLine = rendered.get().get(2).text();
        assertTrue(separatorLine.contains("─") || separatorLine.contains("\u001B["));
    }
}
