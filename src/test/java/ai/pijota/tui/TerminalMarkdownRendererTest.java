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

    @Test
    void renderBlockquote() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("> quote", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("quote"));
    }

    @Test
    void renderUnorderedList() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("- item", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("item"));
    }

    @Test
    void renderOrderedList() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("9. ninth", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("9."));
        assertTrue(result.get(0).text().contains("ninth"));
    }

    @Test
    void renderStrikethrough() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("~~gone~~", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("gone"));
    }

    @Test
    void renderCheckbox() {
        var renderer = new TerminalMarkdownRenderer(theme());
        assertTrue(renderer.render("[x] done", "text", false).get(0).text().contains("done"));
        assertTrue(renderer.render("[ ] todo", "text", false).get(0).text().contains("todo"));
        assertTrue(renderer.render("[~] skip", "text", false).get(0).text().contains("skip"));
    }

    @Test
    void renderWithDefaultItalic() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("italic body", "text", true);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("italic body"));
    }

    @Test
    void disabledThemeRendersPlainText() {
        var disabled = theme();
        disabled.enabled = false;
        var renderer = new TerminalMarkdownRenderer(disabled);
        var result = renderer.render("plain", "text", false);
        assertEquals("plain", result.get(0).text());
    }

    @Test
    void renderHeadingWithLeadingSpaces() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("   # Heading", "text", false);
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).text().contains("Heading"));
    }

    @Test
    void renderPreservesBlankLines() {
        var renderer = new TerminalMarkdownRenderer(theme());
        var result = renderer.render("a\n\nb", "text", false);
        assertEquals(3, result.size());
        assertEquals("", result.get(1).text());
    }
}
