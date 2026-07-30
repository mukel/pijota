package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeysTest {

    @Test
    void nonMacKeepsWrittenModifiers() {
        assertEquals("Ctrl+K", Keys.render("Ctrl+K", false));
        assertEquals("Ctrl+Shift+P", Keys.render("Ctrl+Shift+P", false));
        assertEquals("Alt+_", Keys.render("Alt+_", false));
    }

    @Test
    void macUsesNativeGlyphsTightAgainstTheKey() {
        assertEquals("⌃K", Keys.render("Ctrl+K", true));
        assertEquals("⌥_", Keys.render("Alt+_", true));
        assertEquals("⇧Tab", Keys.render("Shift+Tab", true));
        assertEquals("⌃⇧P", Keys.render("Ctrl+Shift+P", true)); // chained modifiers concatenate
    }

    @Test
    void macRewritesEveryTokenInASentence() {
        assertEquals("Sessions: Enter load, ⌃D delete, ⌃P path",
                Keys.render("Sessions: Enter load, Ctrl+D delete, Ctrl+P path", true));
    }

    @Test
    void baseKeysAndNonShortcutTextAreUntouched() {
        assertEquals("Enter", Keys.render("Enter", true));
        assertEquals("Up/Down", Keys.render("Up/Down", true));
    }
}
