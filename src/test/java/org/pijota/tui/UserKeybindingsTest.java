package org.pijota.tui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class UserKeybindingsTest {
    @TempDir
    Path temp;

    @Test
    void loadsStringAndArrayBindings() throws Exception {
        Path file = temp.resolve("keybindings.json");
        Files.writeString(file, "{\"app.model.select\":\"ctrl+p\",\"app.editor.external\":[\"ctrl+g\"]}");

        var bindings = UserKeybindings.load(file);

        assertEquals(Key.Special.CTRL_P, bindings.keysFor("app.model.select", Key.Special.CTRL_L).getFirst());
        assertEquals(Key.Special.CTRL_G, bindings.keysFor("app.editor.external").getFirst());
    }

    @Test
    void fallsBackToDefaultsForMissingOrInvalidBindings() throws Exception {
        Path file = temp.resolve("keybindings.json");
        Files.writeString(file, "{\"app.model.select\":\"unknown\"}");

        var bindings = UserKeybindings.load(file);

        assertEquals(Key.Special.CTRL_L, bindings.keysFor("app.model.select", Key.Special.CTRL_L).getFirst());
    }

    @Test
    void createsActionRemaps() throws Exception {
        Path file = temp.resolve("keybindings.json");
        Files.writeString(file, "{\"tui.editor.cursorUp\":\"ctrl+p\"}");

        var remaps = UserKeybindings.load(file).remaps(java.util.Map.of("tui.editor.cursorUp", Key.Special.UP));

        assertEquals(Key.Special.UP, remaps.get(Key.Special.CTRL_P));
    }
}
