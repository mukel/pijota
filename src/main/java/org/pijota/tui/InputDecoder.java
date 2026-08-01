package org.pijota.tui;

import org.jline.terminal.Terminal;

import java.io.IOException;

public final class InputDecoder {
    private InputDecoder() {}

    @FunctionalInterface
    interface Input {
        /** Read one byte. Returns -1 on EOF. */
        int read() throws IOException;

        /** Read one byte with timeout. Returns -1 on timeout/EOF. */
        default int read(int timeout) throws IOException {
            return read();
        }
    }

    /**
     * One byte of lookahead. Double-Esc has to peek at the next byte to know whether a second Esc follows, and without
     * somewhere to put a non-Esc byte back that peek would eat a real key: {@code ESC ESC [ A} used to lose the arrow.
     * Scoped to a single readKey call, which is the only place lookahead is needed.
     */
    private static final class Pushback implements Input {
        private final Input delegate;
        private int pending = Integer.MIN_VALUE;

        Pushback(Input delegate) {
            this.delegate = delegate;
        }

        void unread(int ch) {
            pending = ch;
        }

        @Override
        public int read() throws IOException {
            return take(() -> delegate.read());
        }

        @Override
        public int read(int timeout) throws IOException {
            return take(() -> delegate.read(timeout));
        }

        private int take(IoSupplier next) throws IOException {
            if (pending != Integer.MIN_VALUE) {
                int ch = pending;
                pending = Integer.MIN_VALUE;
                return ch;
            }
            return next.get();
        }
    }

    @FunctionalInterface
    private interface IoSupplier {
        int get() throws IOException;
    }

    private static Input input(Terminal terminal) {
        return new Input() {
            @Override
            public int read() throws IOException {
                return terminal.reader().read();
            }

            @Override
            public int read(int timeout) throws IOException {
                return terminal.reader().read(timeout);
            }
        };
    }

    public static Key readKey(Terminal terminal) throws IOException {
        return readKey(input(terminal));
    }

    public static Key readPaste(Terminal terminal) throws IOException {
        return readPaste(input(terminal));
    }

    static Key readKey(Input in) throws IOException {
        return readKey(new Pushback(in));
    }

    private static Key readKey(Pushback in) throws IOException {
        int ch = in.read();
        if (ch == 27)
            return readEscape(in);
        Key control = controlKey(ch);
        if (control != null)
            return control;
        if (ch >= 32)
            return new Key.Char((char) ch);
        return Key.Special.UNKNOWN;
    }

    /**
     * A C0 control byte (or EOF) to its key. Shared with the Kitty CSI-u path: with the protocol enabled a terminal
     * sends {@code CSI 99;5u} instead of the byte 3 for Ctrl+C, so that path converts the letter back to its control
     * byte and lands here rather than restating every binding. Returns null when {@code ch} is not a control byte.
     */
    private static Key controlKey(int ch) {
        return switch (ch) {
            case -1, 4 -> Key.Special.CTRL_D;
            case 1 -> Key.Special.CTRL_A;
            case 2 -> Key.Special.CTRL_B; // pressed twice: move the running tool to the background
            case 3 -> Key.Special.CTRL_C;
            case 5 -> Key.Special.CTRL_E;
            case 7 -> Key.Special.CTRL_G;
            case 11 -> Key.Special.CTRL_K;
            case 12 -> Key.Special.CTRL_L;
            case 15 -> Key.Special.CTRL_O; // expand/collapse the status bar
            case 16 -> Key.Special.CTRL_P;
            case 18 -> Key.Special.CTRL_R;
            case 20 -> Key.Special.CTRL_T; // fzf path completion (raw path, no @)
            case 21 -> Key.Special.CTRL_U;
            case 23 -> Key.Special.CTRL_W;
            case 25 -> Key.Special.CTRL_Y;
            case 24 -> Key.Special.CTRL_X;
            case 26 -> Key.Special.CTRL_Z; // job-control suspend (raw mode swallows the kernel's SIGTSTP)
            case 31 -> Key.Special.CTRL_UNDERSCORE; // Ctrl+_ / Ctrl+/ · readline/zsh undo
            case 9 -> Key.Special.TAB;
            case 10 -> Key.Special.NEWLINE;
            case 13 -> Key.Special.ENTER;
            case 127, 8 -> Key.Special.BACKSPACE;
            default -> null;
        };
    }

    /** The Alt-chords, shared by the legacy {@code ESC <key>} form and the Kitty {@code CSI <key>;3u} form. */
    private static Key altKey(int ch) {
        return switch (ch) {
            case 13, 10 -> Key.Special.ALT_ENTER;
            case 'b', 'B' -> Key.Special.CTRL_LEFT;
            case 'f', 'F' -> Key.Special.CTRL_RIGHT;
            case 'd', 'D' -> Key.Special.ALT_D;
            case 'y', 'Y' -> Key.Special.ALT_Y;
            case '_' -> Key.Special.ALT_UNDERSCORE;
            // Alt+Backspace deletes the previous word in readline/zsh; Ghostty and Terminal.app users expect it.
            case 127, 8 -> Key.Special.CTRL_W;
            default -> null;
        };
    }

    // Bracketed-paste bytes arrive back-to-back; a gap this long means the burst is over or the ESC[201~ terminator was
    // lost. Reading with a timeout (never blocking) keeps a buggy/hostile terminal from wedging the input thread, and
    // the char cap bounds memory against a giant paste. On an incomplete paste we return the best-effort content rather
    // than discard it. A missing terminator is a terminal quirk, not intent to drop the pasted text.
    private static final int PASTE_READ_TIMEOUT_MS = 500;
    private static final int MAX_PASTE_CHARS = 4_000_000;

    static Key readPaste(Input in) throws IOException {
        var sb = new StringBuilder();
        while (sb.length() < MAX_PASTE_CHARS) {
            int ch = in.read(PASTE_READ_TIMEOUT_MS);
            if (ch < 0) // timeout or EOF: the terminator never arrived; stop rather than block forever
                break;
            if (ch == 27) { // maybe the ESC[201~ terminator
                if (in.read(PASTE_READ_TIMEOUT_MS) == '[') {
                    var end = new StringBuilder();
                    int c;
                    while ((c = in.read(PASTE_READ_TIMEOUT_MS)) >= 0 && end.length() < 8) {
                        end.append((char) c);
                        if (c >= 0x40 && c <= 0x7e) // CSI final byte
                            break;
                    }
                    if ("201~".equals(end.toString()))
                        return new Key.Paste(sb.toString());
                }
                break; // a non-terminator ESC ends the paste
            }
            sb.append((char) ch);
        }
        return new Key.Paste(sb.toString());
    }

    /**
     * Decodes one escape sequence, then checks whether a second Esc follows so double-Esc keeps working. Under the
     * Kitty protocol Esc arrives as {@code ESC [ 27 u} rather than a bare byte, so the check has to decode the follower
     * rather than test for a byte; going through the same path handles both encodings.
     */
    private static Key readEscape(Pushback in) throws IOException {
        Key key = readEscapeOnce(in);
        if (key != Key.Special.ESC)
            return key;
        int peek = in.read(20);
        if (peek < 0)
            return Key.Special.ESC;
        if (peek != 27) {
            in.unread(peek);
            return Key.Special.ESC;
        }
        Key second = readEscape(in); // the peeked Esc introduces the next sequence
        return second == Key.Special.ESC ? Key.Special.DOUBLE_ESC : second;
    }

    private static Key readEscapeOnce(Pushback in) throws IOException {
        int next = in.read(20);
        if (next < 0)
            return Key.Special.ESC;
        if (next == 27) { // another escape sequence starts here; readEscape decides what it means
            in.unread(next);
            return Key.Special.ESC;
        }
        Key alt = altKey(next);
        if (alt != null)
            return alt;
        if (next != '[')
            return Key.Special.UNKNOWN;

        var seq = new StringBuilder();
        while (true) {
            int c = in.read(20);
            if (c == -1)
                break;
            seq.append((char) c);
            if (c >= 0x40 && c <= 0x7e)
                break;
        }
        String csi = seq.toString();
        if (csi.endsWith("u"))
            return kittyKey(csi);
        return switch (csi) {
            case "A" -> Key.Special.UP;
            case "1;3A", "3A" -> Key.Special.ALT_UP;
            case "B" -> Key.Special.DOWN;
            case "C" -> Key.Special.RIGHT;
            case "D" -> Key.Special.LEFT;
            case "1;5C", "5C" -> Key.Special.CTRL_RIGHT;
            case "1;5D", "5D" -> Key.Special.CTRL_LEFT;
            case "1;6P", "16P" -> Key.Special.CTRL_SHIFT_P;
            case "H", "1~" -> Key.Special.HOME;
            case "F", "4~" -> Key.Special.END;
            case "5~" -> Key.Special.PG_UP;
            case "6~" -> Key.Special.PG_DOWN;
            case "3~" -> Key.Special.DELETE;
            case "Z" -> Key.Special.SHIFT_TAB;
            case "200~" -> Key.Special.PASTE_START;
            default -> Key.Special.UNKNOWN;
        };
    }

    /**
     * A Kitty {@code CSI <codepoint>[;<modifiers>[:<event>]]u} key. Modifiers are a 1-based bitmask: 1 none, +1 shift,
     * +2 alt, +4 ctrl. Rather than restate every binding, a modified key is folded back onto the encoding the legacy
     * path already understands: Ctrl+letter becomes its C0 control byte, Alt+key goes through the Alt-chord table.
     */
    private static Key kittyKey(String csi) {
        String[] parts = csi.substring(0, csi.length() - 1).split(";");
        if (parts.length == 0)
            return Key.Special.UNKNOWN;
        int codepoint = parseInt(parts[0]);
        // The modifier field can carry an event type after a colon (press/repeat/release); only the mask matters here.
        int mask = parts.length > 1 ? parseInt(parts[1].split(":")[0]) - 1 : 0;
        if (codepoint < 0 || mask < 0)
            return Key.Special.UNKNOWN;
        boolean shift = (mask & 1) != 0, alt = (mask & 2) != 0, ctrl = (mask & 4) != 0;

        if (codepoint == 27 && !shift && !alt && !ctrl)
            return Key.Special.ESC;
        if (ctrl && !alt) {
            if (codepoint >= 'a' && codepoint <= 'z')
                return orUnknown(controlKey(codepoint - 'a' + 1));
            if (codepoint == '_' || codepoint == '/') // both send Ctrl+_ · readline/zsh undo
                return Key.Special.CTRL_UNDERSCORE;
            if (codepoint == 127 || codepoint == 8)
                return Key.Special.CTRL_W; // Ctrl+Backspace deletes the previous word
        }
        if (alt && !ctrl)
            return orUnknown(altKey(codepoint));
        if (shift && !alt && !ctrl) {
            if (codepoint == 13)
                return Key.Special.NEWLINE; // Shift+Enter: a newline in the prompt, not a submit
            if (codepoint == 9)
                return Key.Special.SHIFT_TAB;
        }
        if (mask != 0)
            return Key.Special.UNKNOWN;
        // Unmodified: Enter/Tab/Backspace still arrive as C0 bytes under flag 1, but a terminal is free to send them
        // (and plain printables) in CSI-u form, so accept both rather than drop the keystroke.
        Key control = controlKey(codepoint);
        if (control != null)
            return control;
        return codepoint >= 32 && codepoint <= Character.MAX_VALUE
                                        ? new Key.Char((char) codepoint)
                                        : Key.Special.UNKNOWN;
    }

    private static Key orUnknown(Key key) {
        return key == null ? Key.Special.UNKNOWN : key;
    }

    private static int parseInt(String s) {
        try {
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

}
