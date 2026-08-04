package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything pijota draws that it did not write itself arrives through here, and none of it may command the terminal.
 * Command output, fetched pages, search results, file names, patch text and tool errors are all somebody else's bytes:
 * an ESC among them clears the transcript, homes the cursor over what is already drawn, or renames the window, and any
 * of those lets the producer paint output that appears to be pijota's own.
 *
 * <p>
 * The one thing allowed through is colour, and only on the program-output path, because that is half the reason anyone
 * reads a command block.
 */
class TerminalTextTest {

    private static final String ESC = "\033";
    /** The only escape that may survive: a colour, which paints and does nothing else. */
    private static final Pattern SGR = Pattern.compile("\033\\[[0-9;]*m");

    private static String withoutColour(String text) {
        return SGR.matcher(text).replaceAll("");
    }

    /**
     * Stated as one rule rather than a list of known-bad sequences, so a payload is caught whatever it does. Anything
     * that is not a colour is not a colour, however it is spelled.
     */
    @ParameterizedTest
    @ValueSource(strings = {"[2J", // clear the screen, taking the transcript with it
            "[1;1H", // home the cursor, so what follows overwrites what is above
            "[?1049h", // switch to the alternate screen
            "[10A", // scroll back over rows already read
            "[2K", // erase the line being drawn
            "]0;renamed" + "\007", // rename the window
            "]8;;http://evil\033\\", // a hyperlink pijota never puts in a tool body
            "c", // full reset
            "7" // save the cursor, to restore it somewhere useful later
    })
    void noEscapeThatCommandsTheTerminalSurvives(String payload) {
        String out = TerminalText.printableKeepingColour("before " + ESC + payload + " after");

        assertFalse(withoutColour(out).contains(ESC), () -> "a command survived: " + out.replace(ESC, "<ESC>"));
        assertTrue(out.contains("before ") && out.contains(" after"), "the surrounding text is untouched: " + out);
    }

    /** Disarmed, and still legible: the reader can see what the program actually emitted. */
    @Test
    void theDisarmedPayloadIsStillVisible() {
        assertTrue(TerminalText.printableKeepingColour(ESC + "[2Jx").contains("^[[2J"));
        assertEquals("^@^G^H^?", TerminalText.printableKeepingColour("\0\007\b\177"));
    }

    /** Colour is the exception, and has to be: it is why a command block is worth reading. */
    @ParameterizedTest
    @CsvSource({"'[31m'", "'[0m'", "'[1;32m'", "'[38;2;255;0;0m'", "'[m'", "'[38;5;208m'"})
    void colourPassesThroughUntouched(String sgr) {
        String text = "a" + ESC + sgr + "b";

        assertEquals(text, TerminalText.printableKeepingColour(text), "an SGR sequence paints and does nothing else");
    }

    /** A colour that is not a colour: the parser must reject by default, not scan ahead hopefully for an 'm'. */
    @ParameterizedTest
    @ValueSource(strings = {"[2Jm", // an erase that happens to be followed by an m
            "[?25lm", // a private mode, likewise
            "[1;1Hm" // a cursor move, likewise
    })
    void aCommandFollowedByAnMIsStillACommand(String payload) {
        String out = TerminalText.printableKeepingColour(ESC + payload);

        assertTrue(out.startsWith("^["), () -> "swallowed up to an unrelated m: " + out.replace(ESC, "<ESC>"));
    }

    /** The strict form takes colour too, for the diff, which re-highlights what it draws. */
    @Test
    void theStrictFormKeepsNothingAtAll() {
        assertEquals("^[[31mred", TerminalText.printable(ESC + "[31mred"));
        assertEquals("^[[2J", TerminalText.printable(ESC + "[2J"));
    }

    /** Tab is layout rather than a command, and the wrap already expands it. */
    @Test
    void tabsSurviveBothForms() {
        assertEquals("\tindented", TerminalText.printable("\tindented"));
        assertEquals("\tindented", TerminalText.printableKeepingColour("\tindented"));
    }

    /** The overwhelmingly common line needs nothing done to it and must come back character for character. */
    @ParameterizedTest
    @ValueSource(strings = {"int x = foo(bar, 1);", "  indented text", "var 名前 = \"日本語\";", "emoji 😀 here",
            "trailing spaces   ", "", "a-b_c.d/e"})
    void ordinaryTextIsUnchanged(String line) {
        assertSame(line, TerminalText.printable(line), "an untouched line should not even be copied");
        assertSame(line, TerminalText.printableKeepingColour(line));
    }

    /** A payload after a legitimate colour still gets caught: the scanner must not stop looking. */
    @Test
    void aCommandAfterAColourIsStillCaught() {
        String out = TerminalText.printableKeepingColour(ESC + "[32mgreen" + ESC + "[2Jgone");

        assertTrue(out.startsWith(ESC + "[32m"), "the colour is kept: " + out.replace(ESC, "<ESC>"));
        assertTrue(out.contains("^[[2J"), "and the command that followed it is not: " + out.replace(ESC, "<ESC>"));
    }

    /**
     * The end to end shape of the defect: a command's output cleared the screen the block was being drawn into, so the
     * anchor above it, the block header and the first line of output all vanished.
     */
    @Test
    void aToolBodyCannotClearTheBlockItIsDrawnIn() {
        var lines = ToolResultLines.shellBlock("!", "curl example.com",
                                        "harmless\n" + ESC + "[1;1H" + ESC + "[2JPWNED\ntail", 0, 12, AnsiTheme.dark(),
                                        70);
        String joined = String.join("\n", lines);

        assertFalse(withoutColour(joined).contains(ESC), () -> "a tool body reached the terminal with a command in it: "
                                        + joined.replace(ESC, "<ESC>"));
        assertTrue(joined.contains("^[[2J"), "and the reader can see what it was");
    }

    /** The other half of the trade: a command block that colours its output still colours it. */
    @Test
    void aToolBodyKeepsItsColour() {
        var lines = ToolResultLines.shellBlock("!", "ls --color", ESC + "[32mgreen.txt" + ESC + "[0m", 0, 1,
                                        AnsiTheme.dark(), 70);

        assertTrue(String.join("\n", lines).contains(ESC + "[32m"), "ls --color is why anyone reads this block");
    }
}
