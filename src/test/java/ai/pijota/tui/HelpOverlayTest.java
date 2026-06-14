package ai.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelpOverlayTest {

    @Test
    void contentIncludesCommandsShortcutsAndKeybindings() {
        var out = String.join("\n", HelpOverlay.content(List.of("Commands:", "  /model  switch model"), List.of()));

        assertTrue(out.contains("/model"), out);
        assertTrue(out.contains("keyboard shortcuts"), out);
        assertTrue(out.contains("Shift+Tab") && out.contains("toggle thinking"), out);
        assertTrue(out.contains("Ctrl+_") && out.contains("undo"), out);
    }
}
