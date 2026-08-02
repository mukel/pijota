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
        assertEquals(Key.Special.UNKNOWN, InputDecoder.readKey(input(6))); // Ctrl+F, still unmapped
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

    // ---- Kitty keyboard protocol (CSI <codepoint>;<modifiers> u) ------------------------------------------------
    // With the protocol enabled a terminal reports every modified key this way instead of as a bare control byte, so
    // each of these has to land on exactly the key its legacy encoding produces or the shortcut silently stops working.

    @Test
    void kittyCtrlLettersMapToTheSameKeysAsTheirControlBytes() throws IOException {
        assertEquals(Key.Special.CTRL_C, InputDecoder.readKey(csiU("99;5")), "Ctrl+C must still interrupt");
        assertEquals(Key.Special.CTRL_D, InputDecoder.readKey(csiU("100;5")));
        assertEquals(Key.Special.CTRL_A, InputDecoder.readKey(csiU("97;5")));
        assertEquals(Key.Special.CTRL_E, InputDecoder.readKey(csiU("101;5")));
        assertEquals(Key.Special.CTRL_G, InputDecoder.readKey(csiU("103;5")));
        assertEquals(Key.Special.CTRL_R, InputDecoder.readKey(csiU("114;5")));
        assertEquals(Key.Special.CTRL_U, InputDecoder.readKey(csiU("117;5")));
        assertEquals(Key.Special.CTRL_W, InputDecoder.readKey(csiU("119;5")));
        assertEquals(Key.Special.CTRL_Z, InputDecoder.readKey(csiU("122;5")));
        assertEquals(Key.Special.CTRL_B, InputDecoder.readKey(csiU("98;5")));
    }

    /** Every Ctrl+letter the TUI binds must survive the switch, not just the ones spelled out above. */
    @Test
    void everyCtrlLetterBindingHasTheSameMeaningInBothEncodings() throws IOException {
        for (char c = 'a'; c <= 'z'; c++) {
            Key legacy = InputDecoder.readKey(input(c - 'a' + 1));
            Key kitty = InputDecoder.readKey(csiU((c + 0) + ";5"));
            assertEquals(legacy, kitty, "Ctrl+" + c + " differs between the legacy byte and CSI-u forms");
        }
    }

    @Test
    void kittyReportsShiftEnterWhichTheLegacyEncodingCannotExpress() throws IOException {
        // The whole point of the protocol: a plain terminal sends Shift+Enter as an ordinary Return, so a newline in
        // the prompt was unreachable without Alt or Ctrl+J.
        assertEquals(Key.Special.NEWLINE, InputDecoder.readKey(csiU("13;2")));
        assertEquals(Key.Special.ENTER, InputDecoder.readKey(input(13)), "unmodified Enter still submits");
        assertEquals(Key.Special.ALT_ENTER, InputDecoder.readKey(csiU("13;3")));
    }

    @Test
    void kittyAltChordsMatchTheirEscapePrefixedForms() throws IOException {
        assertEquals(Key.Special.CTRL_LEFT, InputDecoder.readKey(csiU("98;3"))); // alt+b
        assertEquals(Key.Special.CTRL_RIGHT, InputDecoder.readKey(csiU("102;3"))); // alt+f
        assertEquals(Key.Special.ALT_D, InputDecoder.readKey(csiU("100;3")));
        assertEquals(Key.Special.ALT_Y, InputDecoder.readKey(csiU("121;3")));
        assertEquals(Key.Special.ALT_UNDERSCORE, InputDecoder.readKey(csiU("95;3")));
    }

    /**
     * Ctrl+Alt is the keyspace extensions bind into, so it has to arrive from both encodings. It arrived from neither:
     * the Kitty branch fell through {@code ctrl && !alt} and {@code alt && !ctrl} into UNKNOWN, and the legacy form --
     * ESC followed by the letter's control byte -- found nothing in the Alt table. Both shipped extension shortcuts
     * were dead as a result.
     */
    @Test
    void ctrlAltChordsArriveFromBothEncodings() throws IOException {
        assertEquals(Key.Special.CTRL_ALT_P, InputDecoder.readKey(input(27, 16)), "legacy: ESC then Ctrl+P's byte");
        assertEquals(Key.Special.CTRL_ALT_P, InputDecoder.readKey(csiU("112;8")), "kitty: ctrl(4)+alt(2)+1");
        assertEquals(Key.Special.CTRL_ALT_G, InputDecoder.readKey(input(27, 7)));
        assertEquals(Key.Special.CTRL_ALT_G, InputDecoder.readKey(csiU("103;8")));
    }

    /** A Ctrl+Alt letter nothing binds stays UNKNOWN rather than being swallowed as text or as a bare Ctrl chord. */
    @Test
    void anUnboundCtrlAltLetterIsNotMistakenForSomethingElse() throws IOException {
        assertEquals(Key.Special.UNKNOWN, InputDecoder.readKey(csiU("113;8")), "Ctrl+Alt+Q");
        assertEquals(Key.Special.UNKNOWN, InputDecoder.readKey(input(27, 17)));
    }

    @Test
    void altBackspaceDeletesTheWordBeforeTheCursorInBothEncodings() throws IOException {
        // readline/zsh behaviour, and the one Ghostty and Terminal.app users have to configure by hand elsewhere.
        assertEquals(Key.Special.CTRL_W, InputDecoder.readKey(input(27, 127)));
        assertEquals(Key.Special.CTRL_W, InputDecoder.readKey(csiU("127;3")));
        assertEquals(Key.Special.CTRL_W, InputDecoder.readKey(csiU("127;5")), "Ctrl+Backspace too");
    }

    @Test
    void kittyEscapeAndDoubleEscapeStillDistinguishable() throws IOException {
        assertEquals(Key.Special.ESC, InputDecoder.readKey(csiU("27")));
        assertEquals(Key.Special.DOUBLE_ESC,
                                        InputDecoder.readKey(input(27, '[', '2', '7', 'u', 27, '[', '2', '7', 'u')));
        assertEquals(Key.Special.DOUBLE_ESC, InputDecoder.readKey(input(27, 27)), "legacy form unchanged");
    }

    /**
     * Double-Esc has to peek at the byte after the first Esc. Without pushback that peek consumed a real key: ESC
     * followed by an arrow key used to return DOUBLE_ESC and swallow the arrow.
     */
    @Test
    void escapeFollowedByAnotherSequenceDoesNotSwallowIt() throws IOException {
        assertEquals(Key.Special.UP, InputDecoder.readKey(input(27, 27, '[', 'A')));
        assertEquals(Key.Special.CTRL_C, InputDecoder.readKey(input(27, 27, '[', '9', '9', ';', '5', 'u')));
    }

    @Test
    void kittyShiftTabMatchesItsLegacyForm() throws IOException {
        assertEquals(Key.Special.SHIFT_TAB, InputDecoder.readKey(csiU("9;2")));
        assertEquals(Key.Special.SHIFT_TAB, InputDecoder.readKey(input(27, '[', 'Z')));
    }

    @Test
    void kittyUnmodifiedKeysDecodeToTheKeyNotToNothing() throws IOException {
        assertEquals(Key.Special.ENTER, InputDecoder.readKey(csiU("13")));
        assertEquals(Key.Special.TAB, InputDecoder.readKey(csiU("9")));
        assertEquals(Key.Special.BACKSPACE, InputDecoder.readKey(csiU("127")));
        assertEquals(new Key.Char('a'), InputDecoder.readKey(csiU("97")));
    }

    @Test
    void kittyEventTypeSuffixIsIgnoredNotMisread() throws IOException {
        // CSI 99;5:1u -- modifier field carries a press/repeat/release code after a colon.
        assertEquals(Key.Special.CTRL_C, InputDecoder.readKey(csiU("99;5:1")));
    }

    @Test
    void malformedKittySequencesDoNotThrow() throws IOException {
        for (String body : new String[] {"", ";", "abc", "99;", ";5", "99;abc", "-1;5", "999999999999;5"})
            assertDoesNotThrow(() -> InputDecoder.readKey(csiU(body)), "CSI " + body + "u");
    }

    /** CSI <params> u with the protocol's params; builds the byte stream a real terminal would send. */
    private static InputDecoder.Input csiU(String params) {
        var seq = new java.util.ArrayList<Integer>(java.util.List.of(27, (int) '['));
        params.chars().forEach(seq::add);
        seq.add((int) 'u');
        return input(seq.stream().mapToInt(Integer::intValue).toArray());
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
