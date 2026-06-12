package ai.pijota.extensions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeyChordTest {

    @Test
    void keyWithoutModifiers() {
        var chord = new KeyChord(KeyChord.Key.A);
        assertEquals(KeyChord.Key.A, chord.key());
        assertTrue(chord.modifiers().isEmpty());
    }

    @Test
    void keyWithModifiers() {
        var chord = new KeyChord(KeyChord.Key.C, KeyChord.Modifier.CTRL);
        assertEquals(KeyChord.Key.C, chord.key());
        assertTrue(chord.modifiers().contains(KeyChord.Modifier.CTRL));
    }

    @Test
    void keyWithMultipleModifiers() {
        var chord = new KeyChord(KeyChord.Key.D, KeyChord.Modifier.CTRL, KeyChord.Modifier.SHIFT);
        assertEquals(2, chord.modifiers().size());
        assertTrue(chord.modifiers().contains(KeyChord.Modifier.CTRL));
        assertTrue(chord.modifiers().contains(KeyChord.Modifier.SHIFT));
    }

    @Test
    void modifiersAreImmutable() {
        var chord = new KeyChord(KeyChord.Key.A, KeyChord.Modifier.CTRL);
        assertThrows(UnsupportedOperationException.class, () -> chord.modifiers().add(KeyChord.Modifier.ALT));
    }

    @Test
    void equalityByFields() {
        assertEquals(new KeyChord(KeyChord.Key.ENTER), new KeyChord(KeyChord.Key.ENTER));
        assertEquals(new KeyChord(KeyChord.Key.X, KeyChord.Modifier.CTRL),
                                        new KeyChord(KeyChord.Key.X, KeyChord.Modifier.CTRL));
    }

    @Test
    void notEqualWhenKeyDiffers() {
        assertNotEquals(new KeyChord(KeyChord.Key.A), new KeyChord(KeyChord.Key.B));
    }

    @Test
    void notEqualWhenModifiersDiffer() {
        assertNotEquals(new KeyChord(KeyChord.Key.C, KeyChord.Modifier.CTRL),
                                        new KeyChord(KeyChord.Key.C, KeyChord.Modifier.ALT));
    }

    @Test
    void keyEnumHasSpecialKeys() {
        assertNotNull(KeyChord.Key.ENTER);
        assertNotNull(KeyChord.Key.ESC);
        assertNotNull(KeyChord.Key.UP);
        assertNotNull(KeyChord.Key.DOWN);
        assertNotNull(KeyChord.Key.F1);
        assertNotNull(KeyChord.Key.F12);
    }

    @Test
    void modifierEnum() {
        assertNotNull(KeyChord.Modifier.CTRL);
        assertNotNull(KeyChord.Modifier.SHIFT);
        assertNotNull(KeyChord.Modifier.ALT);
        assertEquals(3, KeyChord.Modifier.values().length);
    }
}
