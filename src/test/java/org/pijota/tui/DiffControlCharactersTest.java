package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A diff draws file content, and file content is not trusted. An ESC that reaches the terminal is a command, not a
 * character: {@code ESC[2J} clears the transcript the diff was just added to, {@code ESC[H} moves the cursor, and
 * {@code ESC]0;…} renames the window. All three let whoever wrote the file paint text that appears to come from pijota,
 * which matters more than the glitch it resembles -- the reason to show a diff at all is that the reader can trust it.
 *
 * <p>
 * The guard is one rule, asserted here in the strongest form available: every escape in a rendered diff is a colour.
 */
class DiffControlCharactersTest {

    /** The only escape pijota emits: select-graphic-rendition, which is a colour and nothing else. */
    private static final Pattern SGR = Pattern.compile("\033\\[[0-9;]*m");
    private static final String ESC = "\033";
    private static final AnsiTheme THEME = AnsiTheme.dark();

    private static List<String> render(String oldText, String newText) {
        return new DiffRenderer(THEME).render("edit", "X.java", oldText, newText, 100);
    }

    /** The visible text of a rendered diff, colours removed. Anything else an escape asked for survives this. */
    private static String visible(List<String> rows) {
        return SGR.matcher(String.join("\n", rows)).replaceAll("");
    }

    /**
     * The property that matters, stated once. A payload is caught whatever it does -- clearing, moving, scrolling,
     * renaming the window -- because the assertion is not a list of known-bad sequences but the absence of any escape
     * that is not a colour.
     */
    @ParameterizedTest
    @ValueSource(strings = {"[2J", // clear the screen, taking the transcript with it
            "[1;1H", // home the cursor, so following output overwrites what is above
            "[?1049h", // switch to the alternate screen
            "[10A", // scroll up over rows the reader already read
            "]0;pwned", // rename the terminal window
            "[8m", // conceal: text present, invisible, and copied on selection
            "c" // full reset
    })
    void noEscapeInFileContentEverReachesTheTerminal(String payload) {
        var rows = render("x = 1;\n", "x = \"" + ESC + payload + "ok\";\n");

        assertFalse(visible(rows).contains(ESC), () -> "an escape from file content survived into the drawn diff: "
                                        + visible(rows).replace(ESC, "<ESC>"));
    }

    /** The payload is not merely disarmed, it is shown: the reader can see what was in the file. */
    @Test
    void theDisarmedPayloadIsStillVisibleToTheReader() {
        var rows = render("x = 1;\n", "x = \"" + ESC + "[2J\";\n");

        assertTrue(visible(rows).contains("^[[2J"), "the reader should see what the file actually contained");
    }

    /**
     * Caret notation, the spelling {@code less} and {@code git} already taught every reader of this tool.
     *
     * <p>
     * VT and FF are absent deliberately: {@code \R} counts them as line terminators, so they end a line rather than
     * appear in one, and never reach this guard.
     */
    @ParameterizedTest
    @CsvSource({"0x00, ^@", "0x01, ^A", "0x07, ^G", "0x08, ^H", "0x0E, ^N", "0x1B, ^[", "0x1F, ^_", "0x7F, ^?"})
    void controlCharactersAreShownInCaretNotation(int control, String expected) {
        assertTrue(visible(render("x;\n", "x" + (char) control + ";\n")).contains(expected),
                                        () -> "expected " + expected + " for U+" + Integer.toHexString(control));
    }

    /**
     * Tab is the exception, and has to be: it is layout rather than a command, the wrap already expands it, and a diff
     * that rewrote every indent to {@code ^I} would be unreadable on any tab-indented file.
     */
    @Test
    void tabsSurviveAsIndentationBecauseTheyAreLayoutRatherThanACommand() {
        String rendered = visible(render("\tint x = 1;\n", "\tint x = 2;\n"));

        assertFalse(rendered.contains("^I"), "escaping tabs would mangle every indented line of every file");
        // The wrap expands the tab to its column stop, which is the whole point of leaving it alone.
        assertTrue(rendered.lines().anyMatch(l -> l.matches(".*\\+ {5}int x = 2;.*")),
                                        () -> "a tab-indented line should render indented: " + rendered);
    }

    /**
     * CR and LF end lines, they do not appear in them. A file in CRLF must not render with {@code ^M} down its right
     * edge -- that is how this kind of guard usually announces itself, and it would be worse than the problem.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"a\\r\\nb\\r\\n|a\\r\\nCHANGED\\r\\n", "a\\nb\\n|a\\nCHANGED\\n"})
    void lineTerminatorsAreNotContentAndAreNotEscaped(String before, String after) {
        String rendered = visible(render(before.replace("\\r", "\r").replace("\\n", "\n"),
                                        after.replace("\\r", "\r").replace("\\n", "\n")));

        assertFalse(rendered.contains("^M"), "a CRLF file must not grow a caret at the end of every line");
        assertTrue(rendered.contains("CHANGED"));
    }

    /**
     * Escaping happens before the split feeds anything else, so the diff, the word offsets, the highlighter and the
     * wrap all measure the same string. If it happened later, the emphasis would land left of the word it means.
     */
    @Test
    void wordEmphasisStillLandsCorrectlyOnALineHoldingAnEscape() {
        var rows = render("x = \"" + ESC + "[2Jalpha\";\n", "x = \"" + ESC + "[2Jbeta\";\n");
        String added = rows.stream().filter(r -> visible(List.of(r)).contains("beta")).findFirst().orElseThrow();
        String lit = emphasised(added);

        assertTrue(lit.contains("beta"), () -> "emphasis missed the changed word, lit: [" + lit + "]");
        assertFalse(lit.contains("^["), () -> "emphasis spilled onto the unchanged escaped prefix, lit: [" + lit + "]");
    }

    /** The columns carrying the word-emphasis background, by tracking which background is in force. */
    private static String emphasised(String line) {
        String word = THEME.style("diffAddWord");
        var out = new StringBuilder();
        boolean on = false;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == 0x1b) {
                int end = line.indexOf('m', i);
                String esc = line.substring(i, end + 1);
                if (esc.equals(word))
                    on = true;
                else if (esc.equals("\033[0m") || esc.contains("48;"))
                    on = false;
                i = end;
                continue;
            }
            if (on)
                out.append(line.charAt(i));
        }
        return out.toString();
    }

    /** Line numbers count lines; escaping changes characters within them and must not disturb the count. */
    @Test
    void escapingDoesNotDisturbLineNumbering() {
        var rows = render("a\nb\nc\n", "a\nb" + ESC + "[2J\nc\n");

        assertTrue(visible(rows).lines().anyMatch(l -> l.contains("^[[2J") && l.contains("2")),
                                        "the escaped line is still numbered 2");
    }

    /**
     * A UTF-8 terminal prints C1 controls rather than obeying them, which an escaped terminal confirms, so they are
     * left alone. Pinned because the alternative -- escaping every {@link Character#isISOControl} -- looks more careful
     * and would rewrite legitimate content for no gain.
     */
    @Test
    void c1ControlsAreLeftAloneBecauseAUtf8TerminalPrintsThem() {
        assertTrue(visible(render("x;\n", "xok;\n")).contains("ok"),
                                        "C1 is printed rather than obeyed, so rewriting it would only lose content");
    }

    /**
     * The overwhelmingly common line has nothing to escape and must come through untouched, character for character.
     */
    @ParameterizedTest
    @ValueSource(strings = {"int x = foo(bar, 1);", "  private final String s = \"a b\";", "var 名前 = \"日本語\";",
            "emoji 😀 here", "trailing spaces   ", "mixed ünïcödé and ‹quotes›"})
    void aLineWithNothingToEscapeIsUnchanged(String line) {
        assertTrue(visible(render("placeholder\n", line + "\n")).contains(line),
                                        "ordinary content must survive the guard exactly");
    }

    /** Escaping lengthens lines, so the width invariant is worth re-checking with a payload in play. */
    @Test
    void escapedContentStillNeverExceedsTheTerminalWidth() {
        var rows = new DiffRenderer(THEME).render("edit", "X.java", "x = 1;\n",
                                        "x = \"" + ESC + "[2J" + ESC + "[1;1H" + "padding".repeat(12) + "\";\n", 50);

        for (String row : rows)
            assertTrue(AnsiWidth.visibleWidth(row) <= 50,
                                            () -> "a row " + AnsiWidth.visibleWidth(row) + " columns wide at width 50");
    }
}
