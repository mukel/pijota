package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelpOverlayTest {

    private static String visible(HelpOverlay h) {
        return h.lines().stream().map(AnsiStripper::strip).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void inactiveUntilShown() {
        var h = new HelpOverlay();
        assertFalse(h.active());
        assertTrue(h.lines().isEmpty());
    }

    @Test
    void showBuildsCommandHelpShortcutsAndKeybindings() {
        var h = new HelpOverlay();
        h.show(AnsiTheme.dark(), List.of("Commands:", "  /model  switch model"), List.of());

        assertTrue(h.active());
        String out = visible(h);
        assertTrue(out.contains("/model"), out);
        assertTrue(out.contains("keyboard shortcuts"), out);
        assertTrue(out.contains("Shift+Tab") && out.contains("toggle thinking"), out);
    }

    @Test
    void clearMakesItInactive() {
        var h = new HelpOverlay();
        h.show(AnsiTheme.dark(), List.of("Commands:"), List.of());
        assertTrue(h.active());
        h.clear();
        assertFalse(h.active());
        assertTrue(h.lines().isEmpty());
    }
}
