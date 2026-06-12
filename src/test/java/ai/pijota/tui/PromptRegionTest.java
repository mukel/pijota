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
    void helpOverlayIsEmptyWhenNoLines() {
        var region = new PromptRegion.HelpOverlay();
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of()).withHelpOverlay(List.of());

        assertEquals(0, region.height(80, snapshot));
    }

    @Test
    void helpOverlayRendersTitleAndLines() {
        var region = new PromptRegion.HelpOverlay();
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of()).withHelpOverlay(List.of("line1", "line2"));

        assertEquals(4, region.height(80, snapshot));

        var out = new StringWriter();
        region.render(new PrintWriter(out), 80, THEME, snapshot);
        String rendered = out.toString();

        assertTrue(rendered.contains("help"));
        assertTrue(rendered.contains("line1"));
        assertTrue(rendered.contains("line2"));
        assertTrue(rendered.contains("Esc or q to close"));
    }

    @Test
    void helpOverlayClampsHeight() {
        var region = new PromptRegion.HelpOverlay();
        var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < 30; i++)
            lines.add("line" + i);
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of()).withHelpOverlay(lines);

        assertEquals(1 + 16 + 1, region.height(80, snapshot));
    }

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
