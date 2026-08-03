package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code markdown} setting was parsed in sixteen test files and asserted in none: it chose a renderer and nothing
 * checked which one it chose.
 */
class MarkdownStyleTest {

    @Test
    void plainSelectsThePlainRenderer() {
        assertEquals(MarkdownStyle.PLAIN, MarkdownStyle.from("plain"));
        assertEquals(MarkdownStyle.PLAIN, MarkdownStyle.from("PLAIN"), "a hand-edited settings file may shout");
        assertEquals(MarkdownStyle.PLAIN, MarkdownStyle.from(" plain "));
    }

    @Test
    void everythingElseIsRich() {
        assertEquals(MarkdownStyle.RICH, MarkdownStyle.from("rich"));
        assertEquals(MarkdownStyle.RICH, MarkdownStyle.from(null), "unset is rich");
        assertEquals(MarkdownStyle.RICH, MarkdownStyle.from(""));
        assertEquals(MarkdownStyle.RICH, MarkdownStyle.from("fancy"), "an unknown value degrades to the default");
    }

    /** The two renderers differ where it is visible: a fence language is introduced, or it is not. */
    @Test
    void theChoiceReachesTheFormatter() {
        var rich = new StreamingMarkdownFormatter(AnsiTheme.plain(), "text", false, MarkdownStyle.RICH);
        var plain = new StreamingMarkdownFormatter(AnsiTheme.plain(), "text", false, MarkdownStyle.PLAIN);

        assertNotEquals(render(rich, "# heading"), render(plain, "# heading"),
                                        "rich renders a heading, plain keeps the markers");
    }

    private static String render(StreamingMarkdownFormatter formatter, String line) {
        var out = new StringBuilder();
        formatter.format(line).forEach(styled -> out.append(styled.text()));
        return out.toString();
    }
}
