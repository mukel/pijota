package org.pijota.tui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A strict, minimal terminal emulator for renderer tests: it interprets exactly the control sequences the TUI emits and
 * throws on anything else, so any protocol drift fails loudly instead of silently corrupting the model. It tracks a
 * cols×rows screen plus scrollback, wide glyphs, DECAWM auto-wrap and bottom-row scrolling: precise enough to assert
 * the renderer's core invariant: committed scrollback is never eaten, duplicated, or shifted by prompt redraws.
 */
final class VtEmulator {

    private final int cols;
    private final int rows;
    private final List<int[]> screen = new ArrayList<>(); // cell = codepoint; 0 = continuation cell of a wide glyph
    private final List<String> scrollback = new ArrayList<>();
    private int row;
    private int col; // may equal cols after filling the last cell (VTE-style past-end resting position)
    private boolean autowrap = true;

    VtEmulator(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        for (int i = 0; i < rows; i++)
            screen.add(blankRow());
    }

    void feed(String data) {
        int i = 0;
        while (i < data.length()) {
            char ch = data.charAt(i);
            if (ch == 0x1b) {
                i = escape(data, i);
            } else if (ch == '\r') {
                col = 0;
                i++;
            } else if (ch == '\n') {
                lineFeed();
                i++;
            } else if (ch < 0x20 || ch == 0x7f) {
                throw new AssertionError("raw control char 0x" + Integer.toHexString(ch) + " in terminal output");
            } else {
                int cp = data.codePointAt(i);
                write(cp);
                i += Character.charCount(cp);
            }
        }
    }

    /** Lines that scrolled off the top: committed history the renderer must never touch. */
    List<String> scrollback() {
        return List.copyOf(scrollback);
    }

    /** The visible screen, one string per row, trailing blanks trimmed. */
    List<String> screenLines() {
        return screen.stream().map(VtEmulator::rowText).toList();
    }

    private void write(int cp) {
        int w = glyphWidth(cp);
        if (col + w > cols) {
            if (!autowrap)
                return; // clipped at the right margin; the terminal never materialises an extra row
            lineFeed();
            col = 0;
        }
        int[] r = screen.get(row);
        r[col] = cp;
        if (w == 2)
            r[col + 1] = 0;
        col += w;
    }

    private void lineFeed() {
        if (row == rows - 1) {
            scrollback.add(rowText(screen.remove(0)));
            screen.add(blankRow());
        } else {
            row++;
        }
    }

    private int escape(String data, int i) {
        char kind = data.charAt(i + 1);
        if (kind == '[') {
            int end = i + 2;
            while (end < data.length() && !(data.charAt(end) >= '@' && data.charAt(end) <= '~'))
                end++;
            csi(data.substring(i + 2, end), data.charAt(end));
            return end + 1;
        }
        if (kind == ']') { // OSC (e.g. hyperlinks): consume to BEL or ST, no screen effect
            int end = i + 2;
            while (end < data.length()) {
                if (data.charAt(end) == '\007')
                    return end + 1;
                if (data.charAt(end) == 0x1b && end + 1 < data.length() && data.charAt(end + 1) == '\\')
                    return end + 2;
                end++;
            }
            throw new AssertionError("unterminated OSC sequence");
        }
        throw new AssertionError("unsupported escape ESC " + kind);
    }

    private void csi(String params, char terminator) {
        switch (terminator) {
            case 'A' -> row = Math.max(0, row - param(params, 1));
            case 'C' -> col = Math.min(cols - 1, col + param(params, 1));
            case 'K' -> {
                requireEraseFromCursor(params, "EL");
                clearRowFrom(screen.get(row), col);
            }
            case 'J' -> {
                requireEraseFromCursor(params, "ED");
                clearRowFrom(screen.get(row), col);
                for (int r = row + 1; r < rows; r++)
                    Arrays.fill(screen.get(r), ' ');
            }
            case 'm' -> {} // SGR styling: no layout effect
            case 'h', 'l' -> mode(params, terminator == 'h');
            default -> throw new AssertionError("unsupported CSI " + params + terminator);
        }
    }

    private void mode(String params, boolean set) {
        switch (params) {
            case "?7" -> autowrap = set;
            case "?25", "?2026" -> {} // cursor visibility / synchronized output: no layout effect
            default -> throw new AssertionError("unsupported mode CSI " + params + (set ? 'h' : 'l'));
        }
    }

    private static void requireEraseFromCursor(String params, String name) {
        if (!params.isEmpty() && !"0".equals(params))
            throw new AssertionError(name + " variant " + params + " not emitted by the renderer");
    }

    private void clearRowFrom(int[] r, int from) {
        for (int c = from; c < cols; c++)
            r[c] = ' ';
    }

    private static int param(String params, int fallback) {
        return params.isEmpty() ? fallback : Integer.parseInt(params);
    }

    private static int glyphWidth(int cp) {
        return AnsiWidth.visibleWidth(new String(Character.toChars(cp)));
    }

    private int[] blankRow() {
        int[] r = new int[cols];
        Arrays.fill(r, ' ');
        return r;
    }

    private static String rowText(int[] r) {
        var sb = new StringBuilder();
        for (int cp : r)
            if (cp != 0)
                sb.appendCodePoint(cp);
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ')
            end--;
        return sb.substring(0, end);
    }
}
