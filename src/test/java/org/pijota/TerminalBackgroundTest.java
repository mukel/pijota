package org.pijota;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the COLORFGBG background-brightness parse behind the "auto" theme. */
class TerminalBackgroundTest {

    @Test
    void darkBackgroundIndices() {
        assertEquals(TerminalBackground.Mode.DARK, TerminalBackground.fromColorFgBg("15;0")); // white on black
        assertEquals(TerminalBackground.Mode.DARK, TerminalBackground.fromColorFgBg("7;0"));
        assertEquals(TerminalBackground.Mode.DARK, TerminalBackground.fromColorFgBg("15;8")); // 8 = dark gray
        assertEquals(TerminalBackground.Mode.DARK, TerminalBackground.fromColorFgBg("12;6"));
    }

    @Test
    void lightBackgroundIndices() {
        assertEquals(TerminalBackground.Mode.LIGHT, TerminalBackground.fromColorFgBg("0;15")); // black on white
        assertEquals(TerminalBackground.Mode.LIGHT, TerminalBackground.fromColorFgBg("0;7")); // 7 = light gray
        assertEquals(TerminalBackground.Mode.LIGHT, TerminalBackground.fromColorFgBg("0;9"));
    }

    @Test
    void middleFieldIsIgnoredBackgroundIsLast() {
        assertEquals(TerminalBackground.Mode.LIGHT, TerminalBackground.fromColorFgBg("1;default;15"));
        assertEquals(TerminalBackground.Mode.DARK, TerminalBackground.fromColorFgBg("1;default;0"));
    }

    @Test
    void unknownWhenMissingOrUnparseable() {
        assertEquals(TerminalBackground.Mode.UNKNOWN, TerminalBackground.fromColorFgBg(null));
        assertEquals(TerminalBackground.Mode.UNKNOWN, TerminalBackground.fromColorFgBg(""));
        assertEquals(TerminalBackground.Mode.UNKNOWN, TerminalBackground.fromColorFgBg("1;default")); // bg not numeric
    }
}
