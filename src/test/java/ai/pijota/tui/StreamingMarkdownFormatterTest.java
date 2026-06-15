package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingMarkdownFormatterTest {

    @Test
    void thinkingRendersSameMarkdownStructureAsContentJustDimmed() {
        var theme = AnsiTheme.dark();
        var content = new StreamingMarkdownFormatter(theme);
        var thinking = new StreamingMarkdownFormatter(theme.thinking());
        content.setWidth(60);
        thinking.setWidth(60);

        var lines = java.util.List.of("# Heading", "A **bold** word, an *italic* one, and `inline code`.", "```java",
                                        "int answer = 42;", "```", "| Col A | Col B |", "|-------|-------|",
                                        "| 1     | 2     |", "");
        var c = new StringBuilder();
        var t = new StringBuilder();
        for (String l : lines) {
            for (var sl : content.format(l))
                c.append(sl.text()).append('\n');
            for (var sl : thinking.format(l))
                t.append(sl.text()).append('\n');
        }
        for (var sl : content.flush())
            c.append(sl.text()).append('\n');
        for (var sl : thinking.flush())
            t.append(sl.text()).append('\n');

        String cRaw = c.toString();
        String tRaw = t.toString();
        // Same markdown pipeline → identical structure (heading, bold/italic, syntax-highlighted code, table) once
        // ANSI is stripped: thinking is rendered "the same way" as content.
        assertEquals(AnsiStripper.strip(cRaw), AnsiStripper.strip(tRaw),
                                        "thinking must render the same markdown structure as content");
        // ...but dimmed: the styled bytes differ while thinking still carries markdown/syntax styling.
        assertNotEquals(cRaw, tRaw, "thinking output must be styled differently (dimmed)");
        assertTrue(tRaw.contains("["), "thinking output must still be markdown/syntax styled");
        String stripped = AnsiStripper.strip(tRaw);
        assertTrue(stripped.contains("Heading"), stripped);
        assertTrue(stripped.contains("│"), "table must render with borders: " + stripped);
    }

    @Test
    void rendersCodeFenceMarkersAsStyledVisibleLines() {
        var theme = AnsiTheme.dark();
        var formatter = new StreamingMarkdownFormatter(theme);

        var opening = formatter.format("```python").getFirst().text();
        var code = formatter.format("@lru_cache(maxsize=None)").getFirst().text();
        var closing = formatter.format("```").getFirst().text();

        assertEquals("```python", AnsiStripper.strip(opening));
        assertEquals("@lru_cache(maxsize=None)", AnsiStripper.strip(code));
        assertEquals("```", AnsiStripper.strip(closing));
        assertTrue(opening.contains("\u001B["), "fence marker should be theme styled");
        assertTrue(code.contains("\u001B["), "code should be syntax styled");
    }

    @Test
    void flushClosesUnterminatedFence() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());

        formatter.format("```java");
        formatter.format("class Test {}");

        var flushed = formatter.flush();

        assertEquals("```", AnsiStripper.strip(flushed.getLast().text()));
    }

    @Test
    void formatNullReturnsEmptyLine() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        var lines = formatter.format(null);
        assertEquals(1, lines.size());
        assertEquals("", lines.get(0).text());
    }

    @Test
    void resetClearsFenceAndTableState() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("```java");
        formatter.format("class Test {}");
        formatter.reset();

        var plain = formatter.format("hello");
        assertTrue(AnsiStripper.strip(plain.get(0).text()).contains("hello"));
    }

    @Test
    void setWidthAndThemeDoNotThrow() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.setWidth(40);
        formatter.setTheme(AnsiTheme.light());
        assertFalse(formatter.format("text").isEmpty());
    }

    @Test
    void blankLineFlushesPendingTableHeader() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        var flushed = formatter.format("");
        assertFalse(flushed.isEmpty());
        assertTrue(AnsiStripper.strip(flushed.get(0).text()).contains("| a | b |"));
    }

    @Test
    void tableBufferedUntilSeparatorThenRendered() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        formatter.format("|---|---|");
        formatter.format("| 1 | 2 |");
        var flushed = formatter.flush();

        var joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("a") && s.contains("b")));
        assertTrue(joined.stream().anyMatch(s -> s.contains("1") && s.contains("2")));
    }

    @Test
    void tableFlushedOnBlankLine() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        formatter.format("|---|---|");
        formatter.format("| 1 | 2 |");
        var flushed = formatter.format("");

        var joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("1")));
    }

    @Test
    void headingAfterNonBlankLineGetsSpacer() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("intro");
        var result = formatter.format("# heading");
        assertTrue(result.size() >= 2);
        assertEquals("", AnsiStripper.strip(result.get(0).text()));
        assertTrue(AnsiStripper.strip(result.get(1).text()).contains("heading"));
    }

    @Test
    void tildeFenceMarkersWork() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        var opening = formatter.format("~~~ruby").getFirst().text();
        assertEquals("```ruby", AnsiStripper.strip(opening));
    }

    @Test
    void defaultItalicAndTextSlotAreRespected() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark(), "text", true);
        var lines = formatter.format("plain");
        assertFalse(lines.isEmpty());
        assertEquals("plain", AnsiStripper.strip(lines.get(0).text()));
    }

    @Test
    void codeWithoutHighlighterUsesCodeBodySlot() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("```foo");
        var code = formatter.format("plain code").getFirst().text();
        formatter.format("```");
        assertEquals("plain code", AnsiStripper.strip(code));
        assertTrue(code.contains("\u001B["));
    }

    @Test
    void pendingTableHeaderFlushedWhenNextLineIsNotSeparator() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        var result = formatter.format("not a separator");
        var joined = result.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("| a | b |")));
        assertTrue(joined.stream().anyMatch(s -> s.contains("not a separator")));
    }

    @Test
    void tableFlushedWhenLineLeavesTable() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        formatter.format("|---|---|");
        formatter.format("| 1 | 2 |");
        var result = formatter.format("plain");
        var joined = result.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("1") && s.contains("2")));
        assertTrue(joined.stream().anyMatch(s -> s.contains("plain")));
    }

    @Test
    void fenceLanguageBlankAndPrefix() {
        var noLangFormatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        var noLang = noLangFormatter.format("```").getFirst().text();
        assertEquals("```", AnsiStripper.strip(noLang));

        var prefixedFormatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        var prefixed = prefixedFormatter.format("```language-js").getFirst().text();
        assertEquals("```language-js", AnsiStripper.strip(prefixed));
    }

    @Test
    void invalidTableSeparatorDoesNotFormTable() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        var result = formatter.format("| - | - |");
        var joined = result.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("| a | b |")));
    }
}
