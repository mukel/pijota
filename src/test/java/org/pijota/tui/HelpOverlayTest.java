package org.pijota.tui;

import org.pijota.extensions.ExtensionKeybinding;
import org.pijota.extensions.KeyChord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HelpOverlayTest {

    @Test
    void extensionKeybindingRendersReadableChordNotRawToString() {
        var binding = new ExtensionKeybinding(
                                        new KeyChord(KeyChord.Key.P, KeyChord.Modifier.ALT, KeyChord.Modifier.CTRL),
                                        "Toggle plan mode", () -> {});
        var out = String.join("\n", HelpOverlay.content(List.of("/model"), List.of(binding)));
        assertFalse(out.contains("KeyChord["), "must not print the raw record toString(): " + out);
        assertTrue(out.contains("Ctrl+Alt+P") || out.contains("⌃⌥P"), "shows a readable chord: " + out);
        assertTrue(out.contains("Toggle plan mode"), out);
    }

    @Test
    void contentIncludesCommandsShortcutsAndKeybindings() {
        var out = String.join("\n", HelpOverlay.content(List.of("Commands:", "  /model  switch model"), List.of()));

        assertTrue(out.contains("/model"), out);
        assertTrue(out.contains("keyboard shortcuts"), out);
        // Ctrl/Alt/Shift render as ⌃/⌥/⇧ on macOS (Keys.label), spelled out everywhere else -- either is correct.
        assertTrue((out.contains("Shift+Tab") || out.contains("⇧Tab")) && out.contains("toggle thinking"), out);
        assertTrue((out.contains("Ctrl+_") || out.contains("⌃_")) && out.contains("undo"), out);
        assertTrue(out.contains("press / to search"), out);
        assertTrue(out.contains("Ctrl+P") || out.contains("⌃P"), out);
    }

    @Test
    void helpListsCommandsBeforeShortcuts() {
        var lines = HelpOverlay.content(List.of("/model"), List.of(), false);
        assertTrue(lines.indexOf("/model") < lines.indexOf("keyboard shortcuts"), lines.toString());
    }

    @Test
    void hotkeysListsShortcutsBeforeCommands() {
        var lines = HelpOverlay.content(List.of("/model"), List.of(), true);
        assertTrue(lines.indexOf("keyboard shortcuts") < lines.indexOf("/model"),
                                        "/hotkeys must show shortcuts first: " + lines);
    }

}
