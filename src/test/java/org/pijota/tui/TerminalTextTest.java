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

    /**
     * The model's text is not pijota's either. A model quoting a poisoned file or a fetched page back to the reader
     * carries whatever was in it, and a prompt can ask for the bytes directly -- so the assistant's own output is a
     * likelier carrier than a diff, not a rarer one.
     */
    @ParameterizedTest
    @ValueSource(strings = {"[2J", "[1;1H", "]0;renamed\007", "c"})
    void assistantTextCannotCommandTheTerminal(String payload) {
        String out = new RichMarkdownRenderer(AnsiTheme.dark())
                                        .render("Here is my answer.\n\n" + ESC + payload + "PWNED\n", "text", false)
                                        .stream().map(StyledLine::text).reduce("", String::concat);

        assertFalse(withoutColour(out).contains(ESC),
                                        () -> "the model's text reached the terminal with a command in it: "
                                                                        + out.replace(ESC, "<ESC>"));
        assertTrue(out.contains("Here is my answer."), "and the text around it still rendered");
    }

    /** The plain renderer is the same boundary and gets the same guard. */
    @Test
    void thePlainRendererIsGuardedToo() {
        String out = new TerminalMarkdownRenderer(AnsiTheme.dark())
                                        .render("answer\n" + ESC + "[2JPWNED\n", "text", false).stream()
                                        .map(StyledLine::text).reduce("", String::concat);

        assertFalse(withoutColour(out).contains(ESC), () -> "unguarded: " + out.replace(ESC, "<ESC>"));
    }

    /** Line terminators are structure, not commands: escaping them would turn a document into one long line. */
    @Test
    void aDocumentKeepsItsLineTerminatorsAndTabs() {
        assertEquals("a\nb\r\nc\td", TerminalText.printableMarkdown("a\nb\r\nc\td"));
        assertEquals("a^[[2Jb", TerminalText.printableMarkdown("a" + ESC + "[2Jb"));
        assertNull(TerminalText.printableMarkdown(null));
    }

    /**
     * The guard runs before the parser, so it must not disturb what the parser reads. Markdown is punctuation and
     * whitespace; nothing it uses is a control character.
     */
    @Test
    void theGuardDoesNotDisturbMarkdownItself() {
        var out = new RichMarkdownRenderer(AnsiTheme.dark()).render(
                                        "# Heading\n\n**bold** and `code`\n\n- one\n- two\n\n```java\nint x = 1;\n```\n",
                                        "text", false).stream().map(StyledLine::text).toList();
        // Stripped: a fenced block is syntax highlighted, so its text is not contiguous in the styled output.
        String joined = withoutColour(String.join("\n", out));

        assertTrue(joined.contains("Heading"), joined);
        assertTrue(joined.contains("bold") && joined.contains("code"), joined);
        assertTrue(joined.contains("one") && joined.contains("two"), joined);
        assertTrue(joined.contains("int x = 1;"), "the fenced block still renders: " + joined);
        assertFalse(joined.contains("^"), "and nothing was escaped that should not have been: " + joined);
    }

    /**
     * A block's header carries the call's own arguments -- the command, the path -- and a header has no body's rail to
     * hide behind. The payload does not need to be in what a command printed; being in what it was asked to run is
     * enough.
     */
    @Test
    void aBlockHeaderCannotCommandTheTerminalEither() {
        var lines = ToolResultLines.shellBlock("!", "echo " + ESC + "[1;1H" + ESC + "[2JPWNED", "ok", 0, 5,
                                        AnsiTheme.dark(), 70);
        String joined = String.join("\n", lines);

        assertFalse(withoutColour(joined).contains(ESC), () -> "a command's own text reached the terminal: "
                                        + joined.replace(ESC, "<ESC>"));
        assertTrue(joined.contains("^[[1;1H"), "and the reader can see what was asked for");
    }

    /** The other half of the trade: a command block that colours its output still colours it. */
    @Test
    void aToolBodyKeepsItsColour() {
        var lines = ToolResultLines.shellBlock("!", "ls --color", ESC + "[32mgreen.txt" + ESC + "[0m", 0, 1,
                                        AnsiTheme.dark(), 70);

        assertTrue(String.join("\n", lines).contains(ESC + "[32m"), "ls --color is why anyone reads this block");
    }
}
