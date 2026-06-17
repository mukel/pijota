package ai.pijota.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelegramHtmlTest {

    @Test
    void inlineMarksMapToTelegramTags() {
        assertEquals("<b>bold</b>", TelegramHtml.toHtml("**bold**"));
        assertEquals("<i>it</i>", TelegramHtml.toHtml("*it*"));
        assertEquals("<code>x=1</code>", TelegramHtml.toHtml("`x=1`"));
        assertEquals("<s>old</s>", TelegramHtml.toHtml("~~old~~"));
    }

    @Test
    void headingsDegradeToBold() {
        assertEquals("<b>Title</b>", TelegramHtml.toHtml("## Title"));
    }

    @Test
    void fencedCodeBecomesPreWithContentsEscaped() {
        String out = TelegramHtml.toHtml("```\nif (a < b && c > d) {}\n```");
        assertEquals("<pre>if (a &lt; b &amp;&amp; c &gt; d) {}\n</pre>", out);
    }

    @Test
    void specialCharsInProseAreEscaped() {
        assertEquals("a &lt; b &amp; c &gt; d", TelegramHtml.toHtml("a < b & c > d"));
    }

    @Test
    void linkBecomesAnchorWithEscapedHref() {
        assertEquals("<a href=\"https://x.io/?a=1&amp;b=2\">site</a>",
                                        TelegramHtml.toHtml("[site](https://x.io/?a=1&b=2)"));
    }

    @Test
    void bulletListBecomesPlainBullets() {
        assertEquals("• one\n• two", TelegramHtml.toHtml("- one\n- two"));
    }

    @Test
    void tableFallsBackToMonospaceBlock() {
        String out = TelegramHtml.toHtml("| a | b |\n|---|---|\n| 1 | 2 |");
        assertTrue(out.startsWith("<pre>") && out.endsWith("</pre>"), out);
        assertTrue(out.contains("a | b"), out);
        assertTrue(out.contains("1 | 2"), out);
    }

    @Test
    void partialStreamWithUnclosedFenceStillYieldsWellFormedHtml() {
        // A half-streamed answer: open fence, no close. Must not produce a dangling tag.
        String out = TelegramHtml.toHtml("Here:\n```java\nint x = 1;");
        assertEquals(1, count(out, "<pre>"));
        assertEquals(1, count(out, "</pre>"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length()))
            n++;
        return n;
    }
}
