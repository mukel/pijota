package org.pijota.tui;

/**
 * Rewrites the characters a terminal obeys rather than prints into the caret notation {@code less} and {@code git} show
 * them in.
 *
 * <p>
 * Everything pijota draws that it did not write itself comes through here: command output, fetched pages, search
 * results, file names, patch bodies, tool errors. An ESC in any of those is a command rather than a character.
 * {@code ESC[2J} clears the transcript it is being drawn into, {@code ESC[H} homes the cursor so what follows
 * overwrites what is above, and {@code ESC]0;} renames the window -- and each of them lets whoever produced the text
 * paint output that appears to have come from pijota. That matters more than the display glitch it resembles, because a
 * reader has no way to tell the forged part from the real one.
 *
 * <p>
 * Colour is the single exception, and only where the text is a program's output: {@code ls --color} and a test runner's
 * green dots are half the reason anyone reads that block. An SGR sequence can forge a colour and nothing else -- it
 * cannot move the cursor, clear the screen or rename the window -- so allowing it keeps the feature and gives up
 * little. A diff takes the strict form instead, since it re-highlights what it draws and an escape carried in from the
 * file would land in the middle of that.
 */
final class TerminalText {

    private static final char DEL = '\177';

    private TerminalText() {}

    /** Every control character and escape sequence made visible. */
    static String printable(String line) {
        return escape(line, false);
    }

    /** The same, except that SGR colour passes through untouched. */
    static String printableKeepingColour(String line) {
        return escape(line, true);
    }

    private static String escape(String line, boolean keepColour) {
        StringBuilder out = null; // stays null while the line needs nothing done to it, which is the common case
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (keepColour && ch == 0x1b && isColour(line, i)) {
                int end = AnsiWidth.skipEscape(line, i);
                if (out != null)
                    out.append(line, i, end + 1);
                i = end;
                continue;
            }
            if (ch >= ' ' && ch != DEL || ch == '\t') { // tab is layout, and the wrap already expands it
                if (out != null)
                    out.append(ch);
                continue;
            }
            if (out == null)
                out = new StringBuilder(line.length() + 8).append(line, 0, i);
            out.append('^').append((char) (ch ^ 0x40)); // NUL -> @, ESC -> [, DEL -> ?
        }
        return out == null ? line : out.toString();
    }

    /**
     * Whether the escape at {@code at} selects a graphic rendition: {@code ESC [}, digits and semicolons, {@code m}.
     *
     * <p>
     * Spelled out rather than taken from {@link AnsiWidth#skipEscape} and then inspected, because the question is not
     * where the sequence ends but whether it is the one kind that only paints. Anything else -- a cursor move, an
     * erase, a private mode, an OSC -- is a command and gets escaped, so the test has to reject by default.
     */
    private static boolean isColour(String line, int at) {
        if (at + 1 >= line.length() || line.charAt(at + 1) != '[')
            return false;
        for (int i = at + 2; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == 'm')
                return true;
            if (ch != ';' && (ch < '0' || ch > '9'))
                return false;
        }
        return false;
    }
}
