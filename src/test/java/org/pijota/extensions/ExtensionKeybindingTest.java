package org.pijota.extensions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionKeybindingTest {

    @Test
    void storesKeyChordAndDescription() {
        var chord = new KeyChord(KeyChord.Key.S, KeyChord.Modifier.CTRL);
        var kb = new ExtensionKeybinding(chord, "save", () -> {});
        assertEquals(chord, kb.key());
        assertEquals("save", kb.description());
        assertNotNull(kb.handler());
    }

    @Test
    void handlerIsExecutable() {
        var called = new boolean[1];
        var kb = new ExtensionKeybinding(new KeyChord(KeyChord.Key.A), "test", () -> called[0] = true);
        kb.handler().run();
        assertTrue(called[0]);
    }

    @Test
    void equalityByFields() {
        var handler = (Runnable) () -> {};
        assertEquals(new ExtensionKeybinding(new KeyChord(KeyChord.Key.X), "desc", handler),
                                        new ExtensionKeybinding(new KeyChord(KeyChord.Key.X), "desc", handler));
    }
}
