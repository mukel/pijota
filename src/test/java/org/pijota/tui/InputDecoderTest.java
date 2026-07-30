package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class InputDecoderTest {
    @Test
    void mapsControlCharactersToSpecialKeys() throws IOException {
        assertEquals(Key.Special.CTRL_A, InputDecoder.readKey(input(1)));
        assertEquals(Key.Special.CTRL_C, InputDecoder.readKey(input(3)));
        assertEquals(Key.Special.CTRL_D, InputDecoder.readKey(input(4)));
        assertEquals(Key.Special.CTRL_E, InputDecoder.readKey(input(5)));
        assertEquals(Key.Special.CTRL_G, InputDecoder.readKey(input(7)));
        assertEquals(Key.Special.CTRL_K, InputDecoder.readKey(input(11)));
        assertEquals(Key.Special.CTRL_U, InputDecoder.readKey(input(21)));
        assertEquals(Key.Special.CTRL_W, InputDecoder.readKey(input(23)));
        assertEquals(Key.Special.CTRL_R, InputDecoder.readKey(input(18)));
        assertEquals(Key.Special.CTRL_T, InputDecoder.readKey(input(20)), "Ctrl+T → fzf path completion");
        assertEquals(Key.Special.CTRL_O, InputDecoder.readKey(input(15)), "Ctrl+O → expand/collapse status");
        assertEquals(Key.Special.CTRL_Y, InputDecoder.readKey(input(25)));
        assertEquals(Key.Special.CTRL_UNDERSCORE, InputDecoder.readKey(input(31)));
        assertEquals(Key.Special.CTRL_X, InputDecoder.readKey(input(24)));
        assertEquals(Key.Special.CTRL_Z, InputDecoder.readKey(input(26)), "Ctrl+Z → job-control suspend key");
    }

    @Test
    void mapsPrintableCharactersToCharKey() throws IOException {
        assertEquals(new Key.Char('a'), InputDecoder.readKey(input('a')));
        assertEquals(new Key.Char('Z'), InputDecoder.readKey(input('Z')));
        assertEquals(new Key.Char(' '), InputDecoder.readKey(input(' ')));
    }

    @Test
    void mapsSpecialCharactersToNamedKeys() throws IOException {
        assertEquals(Key.Special.TAB, InputDecoder.readKey(input(9)));
        assertEquals(Key.Special.NEWLINE, InputDecoder.readKey(input(10)));
        assertEquals(Key.Special.ENTER, InputDecoder.readKey(input(13)));
        assertEquals(Key.Special.BACKSPACE, InputDecoder.readKey(input(127)));
    }

    @Test
    void mapsEscapeSequencesToArrowAndNavigationKeys() throws IOException {
        assertEquals(Key.Special.UP, InputDecoder.readKey(input(27, '[', 'A')));
        assertEquals(Key.Special.DOWN, InputDecoder.readKey(input(27, '[', 'B')));
        assertEquals(Key.Special.RIGHT, InputDecoder.readKey(input(27, '[', 'C')));
        assertEquals(Key.Special.LEFT, InputDecoder.readKey(input(27, '[', 'D')));
        assertEquals(Key.Special.HOME, InputDecoder.readKey(input(27, '[', 'H')));
        assertEquals(Key.Special.END, InputDecoder.readKey(input(27, '[', 'F')));
        assertEquals(Key.Special.DELETE, InputDecoder.readKey(input(27, '[', '3', '~')));
        assertEquals(Key.Special.SHIFT_TAB, InputDecoder.readKey(input(27, '[', 'Z')));
    }

    @Test
    void mapsEscapeAloneToEscapeAndDoubleEscape() throws IOException {
        assertEquals(Key.Special.ESC, InputDecoder.readKey(input(27)));
        assertEquals(Key.Special.DOUBLE_ESC, InputDecoder.readKey(input(27, 27)));
    }

    @Test
    void mapsAltAndCtrlArrowModifiersToNamedKeys() throws IOException {
        assertEquals(Key.Special.ALT_D, InputDecoder.readKey(input(27, 'd')));
        assertEquals(Key.Special.CTRL_RIGHT, InputDecoder.readKey(input(27, 'f')));
        assertEquals(Key.Special.ALT_Y, InputDecoder.readKey(input(27, 'y')));
        assertEquals(Key.Special.ALT_UNDERSCORE, InputDecoder.readKey(input(27, '_')));
    }

    @Test
    void mapsUnknownSequencesToUnknownKey() throws IOException {
        assertEquals(Key.Special.UNKNOWN, InputDecoder.readKey(input(27, '[', 'X')));
    }

    @Test
    void readsPasteWithBracketedPasteTerminator() throws IOException {
        var paste = InputDecoder.readPaste(input('h', 'i', 27, '[', '2', '0', '1', '~'));
        assertEquals(new Key.Paste("hi"), paste);
    }

    @Test
    void readPasteReturnsBestEffortContentWhenTerminatorIsMissing() throws IOException {
        // A lost bracketed-paste terminator (EOF, or a non-201~ ESC) must not wedge the input thread: return the
        // content read so far rather than blocking or discarding it.
        assertEquals(new Key.Paste("hi"), InputDecoder.readPaste(input('h', 'i')));
        assertEquals(new Key.Paste("hi"), InputDecoder.readPaste(input('h', 'i', 27, '[', 'A')));
    }

    @Test
    void mapsCtrlXAsPrefixKey() throws IOException {
        assertEquals(Key.Special.CTRL_X, InputDecoder.readKey(input(24)));
    }

    @Test
    void mapsAdditionalControlKeys() throws IOException {
        assertEquals(Key.Special.CTRL_L, InputDecoder.readKey(input(12)));
        assertEquals(Key.Special.CTRL_P, InputDecoder.readKey(input(16)));
    }

    @Test
    void mapsCtrlBForBackgrounding() throws IOException {
        assertEquals(Key.Special.CTRL_B, InputDecoder.readKey(input(2)));
    }

    @Test
    void mapsUnknownControlCharToUnknown() throws IOException {
        assertEquals(Key.Special.UNKNOWN, InputDecoder.readKey(input(6))); // Ctrl+F — still unmapped
    }

    @Test
    void mapsBackspaceFromChar8() throws IOException {
        assertEquals(Key.Special.BACKSPACE, InputDecoder.readKey(input(8)));
    }

    @Test
    void mapsPageKeysAndShiftTab() throws IOException {
        assertEquals(Key.Special.PG_UP, InputDecoder.readKey(input(27, '[', '5', '~')));
        assertEquals(Key.Special.PG_DOWN, InputDecoder.readKey(input(27, '[', '6', '~')));
        assertEquals(Key.Special.SHIFT_TAB, InputDecoder.readKey(input(27, '[', 'Z')));
    }

    @Test
    void mapsHomeAndEndVariants() throws IOException {
        assertEquals(Key.Special.HOME, InputDecoder.readKey(input(27, '[', '1', '~')));
        assertEquals(Key.Special.END, InputDecoder.readKey(input(27, '[', '4', '~')));
    }

    @Test
    void mapsPasteStartAndModifierVariants() throws IOException {
        assertEquals(Key.Special.PASTE_START, InputDecoder.readKey(input(27, '[', '2', '0', '0', '~')));
        assertEquals(Key.Special.CTRL_RIGHT, InputDecoder.readKey(input(27, '[', '5', 'C')));
        assertEquals(Key.Special.CTRL_LEFT, InputDecoder.readKey(input(27, '[', '5', 'D')));
        assertEquals(Key.Special.CTRL_SHIFT_P, InputDecoder.readKey(input(27, '[', '1', '6', 'P')));
    }

    @Test
    void escapeFollowedByNewlineReturnsAltEnter() throws IOException {
        assertEquals(Key.Special.ALT_ENTER, InputDecoder.readKey(input(27, 13)));
        assertEquals(Key.Special.ALT_ENTER, InputDecoder.readKey(input(27, 10)));
    }

    @Test
    void mapsAltUp() throws IOException {
        assertEquals(Key.Special.ALT_UP, InputDecoder.readKey(input(27, '[', '1', ';', '3', 'A')));
        assertEquals(Key.Special.ALT_UP, InputDecoder.readKey(input(27, '[', '3', 'A')));
    }

    @Test
    void incompleteEscapeSequenceReturnsUnknown() throws IOException {
        assertEquals(Key.Special.UNKNOWN, InputDecoder.readKey(input(27, '[')));
    }

    @Test
    void ctrlXReturnsCtrlX() throws IOException {
        assertEquals(Key.Special.CTRL_X, InputDecoder.readKey(input(24, 5)));
        assertEquals(Key.Special.CTRL_X, InputDecoder.readKey(input(24, 9)));
    }

    @Test
    void escapeWithNoBracketReturnsUnknown() throws IOException {
        assertEquals(Key.Special.UNKNOWN, InputDecoder.readKey(input(27, 'Z')));
    }

    private static InputDecoder.Input input(int... chars) {
        var it = Arrays.stream(chars).iterator();
        return new InputDecoder.Input() {
            @Override
            public int read() {
                return it.hasNext() ? it.next() : -1;
            }
        };
    }
}
