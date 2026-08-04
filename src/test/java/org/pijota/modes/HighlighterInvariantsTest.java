package org.pijota.modes;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Word-level diff emphasis is computed on raw text and painted onto highlighted text, and it paints with a background.
 * Three properties of this highlighter are what let that be a single pass with no state machine. None of them was
 * guarded, and any of them changing would break the emphasis silently: the colours would still look right and the
 * highlight would land in the wrong columns, or vanish after the first coloured token.
 */
class HighlighterInvariantsTest {

    private static final Pattern ANY_ESCAPE = Pattern.compile("\033\\[[0-9;]*m");
    /** An SGR parameter of 40-47, 49, or 48;… sets or clears a background. */
    private static final Pattern SETS_BACKGROUND = Pattern.compile("\033\\[[0-9;]*(4[0-9]|10[0-7])(;[0-9;]*)?m");

    private static String highlight(String source, String filename) {
        return new SyntaxHighlighter(SyntaxHighlighter.languageForFilename(filename), AnsiTheme.dark())
                                        .highlightLine(source);
    }

    private static String stripAnsi(String s) {
        return ANY_ESCAPE.matcher(s).replaceAll("");
    }

    /**
     * The mapping from a raw offset to a position in the highlighted string is "count the non-escape characters". That
     * only holds if highlighting adds escapes and changes nothing else: no tab expansion, no trimming, no substitution.
     */
    @ParameterizedTest
    @ValueSource(strings = {"int x = foo(bar, 1); // hi", "  private final String s = \"a b\";",
            "public record R(int a) {}", "\tif (a && b) { return; }", "", "   ", "x", "// just a comment",
            "String s = \"a \\\" quoted \\\" thing\";", "var 名前 = \"日本語\"; // ok"})
    void highlightingOnlyInsertsEscapes(String line) {
        assertEquals(line, stripAnsi(highlight(line, "X.java")),
                                        "emphasis offsets are raw-text offsets; altering the text breaks every one");
    }

    /**
     * Emphasis is a background. If a syntax opener also set one, it would overwrite the emphasis mid-word and the
     * highlight would end early with no reset in sight to restore it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"int x = foo(\"s\", 1); // c", "public static void main(String[] a) {}",
            "return a == b ? 1 : 0;"})
    void noSyntaxColourEverSetsABackground(String line) {
        var matcher = SETS_BACKGROUND.matcher(highlight(line, "X.java"));
        assertFalse(matcher.find(), () -> "a syntax colour set a background (" + matcher.group().replace("\033", "ESC")
                                        + "), which would overwrite the emphasis");
    }

    /**
     * The overlay re-emits the applicable background after every full reset. A partial close ({@code ESC[39m}, say)
     * would clear nothing and need no re-emission, but a *different* full-reset spelling would clear the background
     * without being recognised, so the highlight would die at the first coloured token.
     */
    @Test
    void everyResetIsSpelledExactlyAsAFullReset() {
        String highlighted = highlight("int x = foo(\"s\", 1); // c", "X.java");
        var matcher = ANY_ESCAPE.matcher(highlighted);
        while (matcher.find()) {
            String esc = matcher.group();
            String params = esc.substring(2, esc.length() - 1);
            boolean clearsEverything = params.isEmpty() || params.equals("0");
            assertEquals(clearsEverything, esc.equals("\033[0m"), () -> "a reset spelled " + esc.replace("\033", "ESC")
                                            + " is not the one the overlay looks for");
        }
        assertTrue(highlighted.contains("\033[0m"), "fixture check: this line really is coloured");
    }
}
