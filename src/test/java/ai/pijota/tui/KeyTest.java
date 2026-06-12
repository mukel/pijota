package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeyTest {

    @Test
    void charKeyStoresCharacter() {
        var key = new Key.Char('a');
        assertEquals('a', key.value());
    }

    @Test
    void pasteKeyStoresText() {
        var key = new Key.Paste("hello world");
        assertEquals("hello world", key.text());
    }

    @Test
    void allSpecialsAreEnumerated() {
        assertTrue(Key.Special.values().length > 0);
    }

    @Test
    void enterIsSpecial() {
        var key = Key.Special.ENTER;
        assertTrue(key instanceof Key);
    }

    @Test
    void backspaceIsSpecial() {
        assertEquals(Key.Special.BACKSPACE, Key.Special.valueOf("BACKSPACE"));
    }

    @Test
    void navigationKeysExist() {
        assertNotNull(Key.Special.LEFT);
        assertNotNull(Key.Special.RIGHT);
        assertNotNull(Key.Special.UP);
        assertNotNull(Key.Special.DOWN);
    }

    @Test
    void controlKeysExist() {
        assertNotNull(Key.Special.CTRL_A);
        assertNotNull(Key.Special.CTRL_C);
        assertNotNull(Key.Special.CTRL_D);
        assertNotNull(Key.Special.CTRL_K);
        assertNotNull(Key.Special.CTRL_U);
        assertNotNull(Key.Special.CTRL_W);
    }

    @Test
    void pasteAndUnknownAreSpecials() {
        assertNotNull(Key.Special.PASTE_START);
        assertNotNull(Key.Special.UNKNOWN);
    }
}
