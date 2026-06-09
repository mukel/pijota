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
}
