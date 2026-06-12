package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerminalMarkdownRendererTest {

    private static AnsiTheme theme() {
        return AnsiTheme.dark();
    }

    @Test
    void renderEmptyReturnsEmpty() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("", "text", false);
        assertTrue(result.isEmpty());
    }

    @Test
    void renderNullReturnsEmpty() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render(null, "text", false);
        assertTrue(result.isEmpty());
    }

    @Test
    void renderPlainText() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("hello world", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("hello"));
    }

    @Test
    void renderMultipleLines() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("line1\nline2\nline3", "text", false);
        assertEquals(3, result.size());
    }

    @Test
    void setWidthClampsToAtLeastOne() {
        var renderer = new TerminalMarkdownRenderer(theme());
        renderer.setWidth(0);
        renderer.render("text", "text", false);
    }

    @Test
    void setWidthNormal() {
        var renderer = new TerminalMarkdownRenderer(theme());
        renderer.setWidth(40);
        renderer.render("a longer text that might wrap", "text", false);
    }

    @Test
    void setThemeChangesTheme() {
        var renderer = new TerminalMarkdownRenderer(theme());
        renderer.setTheme(AnsiTheme.light());
        var result = renderer.render("text", "text", false);
        assertFalse(result.isEmpty());
    }

    @Test
    void renderWithHeadings() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("# Heading 1\n\ntext", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(l -> l.text().contains("Heading")));
    }

    @Test
    void renderWithBold() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("**bold text**", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("bold"));
    }

    @Test
    void renderWithItalic() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("*italic text*", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("italic"));
    }

    @Test
    void renderWithCodeInline() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("use `var x = 1` here", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("var"));
    }

    @Test
    void renderWithCodeBlock() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("```java\nint x = 1;\n```", "text", false);
        assertFalse(result.isEmpty());
    }

    @Test
    void renderTableDelegatesToTableRenderer() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("| a | b |\n| - | - |\n| 1 | 2 |", "text", false);
        assertFalse(result.isEmpty());
    }
}
