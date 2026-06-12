package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingMarkdownFormatterTest {
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
        formatter.format("| - | - |");
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
        formatter.format("| - | - |");
        formatter.format("| 1 | 2 |");
        formatter.format("");

        var flushed = formatter.flush();
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
        assertEquals("~~~ruby", AnsiStripper.strip(opening));
    }

    @Test
    void defaultItalicAndTextSlotAreRespected() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark(), "text", true);
        var lines = formatter.format("plain");
        assertFalse(lines.isEmpty());
        assertEquals("plain", AnsiStripper.strip(lines.get(0).text()));
    }
}
