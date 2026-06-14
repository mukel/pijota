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
}
