package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptRegionTest {
    private static final AnsiTheme THEME = AnsiTheme.dark();

    @Test
    void editorRendersSuggestionWhenCursorAtEnd() {
        var region = new PromptRegion.Editor();
        var snapshot = new PromptSnapshot(List.of("/se"), 0, 3, List.of()).withSuggestion("/sessions");

        var out = new StringWriter();
        region.render(new PrintWriter(out), 80, THEME, snapshot);
        String rendered = out.toString();

        assertTrue(rendered.contains("/se"));
        assertTrue(rendered.contains("/sessions"));
    }

    @Test
    void editorDoesNotRenderSuggestionWhenCursorNotAtEnd() {
        var region = new PromptRegion.Editor();
        var snapshot = new PromptSnapshot(List.of("/se"), 0, 2, List.of()).withSuggestion("/sessions");

        var out = new StringWriter();
        region.render(new PrintWriter(out), 80, THEME, snapshot);
        String rendered = out.toString();

        assertTrue(rendered.contains("/se"));
        assertFalse(rendered.contains("/sessions"));
    }

    @Test
    void completionRowsNeverExceedWidthSoFrameHeightStaysAccurate() {
        // A row wider than the terminal auto-wraps to 2 physical rows, but height() counts 1 per item — that mismatch
        // desyncs PromptLayout and stacks stale separators. Every rendered row must stay within width.
        var region = new PromptRegion.Completions();
        int width = 40;
        var items = List.of(new CompletionItem("/a-very-long-command-name-that-overflows-the-line-by-a-lot",
                                        "and a description that is itself far longer than the terminal is wide"),
                                        new CompletionItem("/short", "brief"));
        var snapshot = new PromptSnapshot(List.of("/a"), 0, 2, items, 0);

        var out = new StringWriter();
        region.render(new PrintWriter(out), width, THEME, snapshot);
        String[] rows = out.toString().split("\n", -1);

        for (String row : rows)
            assertTrue(AnsiWidth.visibleWidth(row) <= width, "row exceeds width " + width + " ("
                                            + AnsiWidth.visibleWidth(row) + "): " + row);
        assertEquals(region.height(width, snapshot), rows.length, "rendered rows must match measured height");
    }
}
