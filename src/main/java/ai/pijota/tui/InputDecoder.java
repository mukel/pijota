package ai.pijota.tui;

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
        int ch = in.read();
        if (ch == -1 || ch == 4)
            return Key.Special.CTRL_D;
        if (ch == 1)
            return Key.Special.CTRL_A;
        if (ch == 3)
            return Key.Special.CTRL_C;
        if (ch == 5)
            return Key.Special.CTRL_E;
        if (ch == 7)
            return Key.Special.CTRL_G;
        if (ch == 11)
            return Key.Special.CTRL_K;
        if (ch == 12)
            return Key.Special.CTRL_L;
        if (ch == 16)
            return Key.Special.CTRL_P;
        if (ch == 15)
            return Key.Special.CTRL_O; // expand/collapse the status bar
        if (ch == 18)
            return Key.Special.CTRL_R;
        if (ch == 20)
            return Key.Special.CTRL_T; // fzf path completion (raw path, no @)
        if (ch == 21)
            return Key.Special.CTRL_U;
        if (ch == 23)
            return Key.Special.CTRL_W;
        if (ch == 25)
            return Key.Special.CTRL_Y;
        if (ch == 31)
            return Key.Special.CTRL_UNDERSCORE; // Ctrl+_ / Ctrl+/ — readline/zsh undo
        if (ch == 24)
            return Key.Special.CTRL_X;
        if (ch == 26)
            return Key.Special.CTRL_Z; // job-control suspend (raw mode swallows the kernel's SIGTSTP)
        if (ch == 9)
            return Key.Special.TAB;
        if (ch == 10)
            return Key.Special.NEWLINE;
        if (ch == 13)
            return Key.Special.ENTER;
        if (ch == 127 || ch == 8)
            return Key.Special.BACKSPACE;
        if (ch == 27)
            return readEscape(in);
        if (ch >= 32)
            return new Key.Char((char) ch);
        return Key.Special.UNKNOWN;
    }

    static Key readPaste(Input in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int ch = in.read();
            if (ch == 27) {
                int next = in.read();
                if (next == -1)
                    return null;
                if (next == '[') {
                    var end = new StringBuilder();
                    while (true) {
                        int c = in.read();
                        if (c == -1)
                            return null;
                        end.append((char) c);
                        if (c >= 0x40 && c <= 0x7e)
                            break;
                    }
                    if ("201~".equals(end.toString()))
                        return new Key.Paste(sb.toString());
                }
                return null;
            }
            if (ch == -1)
                return null;
            sb.append((char) ch);
        }
    }

    private static Key readEscape(Input in) throws IOException {
        int next = in.read(20);
        if (next < 0)
            return Key.Special.ESC;
        if (next == 27)
            return Key.Special.DOUBLE_ESC;
        if (next == 13 || next == 10)
            return Key.Special.ALT_ENTER;
        if (next == 'b' || next == 'B')
            return Key.Special.CTRL_LEFT;
        if (next == 'f' || next == 'F')
            return Key.Special.CTRL_RIGHT;
        if (next == 'd' || next == 'D')
            return Key.Special.ALT_D;
        if (next == 'y' || next == 'Y')
            return Key.Special.ALT_Y;
        if (next == '_')
            return Key.Special.ALT_UNDERSCORE;
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
        return switch (seq.toString()) {
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

}
