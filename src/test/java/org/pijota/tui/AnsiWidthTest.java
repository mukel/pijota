package org.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnsiWidthTest {

    @Test
    void visibleWidthPlainAscii() {
        assertEquals(5, AnsiWidth.visibleWidth("hello"));
    }

    @Test
    void visibleWidthEmptyString() {
        assertEquals(0, AnsiWidth.visibleWidth(""));
    }

    @Test
    void visibleWidthStripsAnsiEscapes() {
        var text = "\033[31mred\033[0m";
        assertEquals(3, AnsiWidth.visibleWidth(text));
    }

    @Test
    void visibleWidthAnsiWithMultipleCodes() {
        var text = "\033[1;31mBOLD RED\033[0m";
        assertEquals(8, AnsiWidth.visibleWidth(text));
    }

    @Test
    void visibleWidthTabExpandsToBoundary() {
        assertEquals(4, AnsiWidth.visibleWidth("\t"));
        assertEquals(4, AnsiWidth.visibleWidth("a\t"));
    }

    @Test
    void visibleWidthControlCharsSkipped() {
        assertEquals(1, AnsiWidth.visibleWidth("a\033b"));
    }

    @Test
    void visibleWidthCjkCharacters() {
        var text = "你好";
        assertEquals(4, AnsiWidth.visibleWidth(text));
    }

    @Test
    void visibleWidthEmoji() {
        var text = "🙈";
        assertEquals(2, AnsiWidth.visibleWidth(text));
        assertTrue(AnsiWidth.visibleWidth(text) >= 1);
    }

    @Test
    void visibleWidthZwjSequence() {
        assertEquals(1, AnsiWidth.visibleWidth("\u200D"));
    }

    @Test
    void truncateVisualEmptyWhenMaxZeroOrNegative() {
        assertEquals("", AnsiWidth.truncateVisual("hello", 0));
        assertEquals("", AnsiWidth.truncateVisual("hello", -1));
    }

    @Test
    void truncateVisualKeepsShortText() {
        assertEquals("hello", AnsiWidth.truncateVisual("hello", 10));
    }

    @Test
    void truncateVisualCutsAtWidth() {
        var result = AnsiWidth.truncateVisual("hello world", 5);
        assertTrue(result.startsWith("hello"));
        assertTrue(result.endsWith("\033[0m"));
    }

    @Test
    void wrapPreservingAnsi_emptyForZeroOrNegativeWidth() {
        assertEquals(List.of(""), AnsiWidth.wrapPreservingAnsi("hello", 0));
    }

    @Test
    void wrapPreservingAnsi_singleLineWithinWidth() {
        var lines = AnsiWidth.wrapPreservingAnsi("short text", 20);
        assertEquals(1, lines.size());
        assertEquals("short text", lines.get(0));
    }

    @Test
    void wrapPreservingAnsi_wrapsAtWidth() {
        var lines = AnsiWidth.wrapPreservingAnsi("hello world", 6);
        assertTrue(lines.size() >= 2, "should produce at least 2 lines, got " + lines.size());
    }

    @Test
    void wrapPreservingAnsiBreaksAtWordBoundaryNotMidWord() {
        // "hello world" at width 8 must wrap at the space (-> "hello" / "world"), not slice the word ("hello wo" / "rld").
        var lines = AnsiWidth.wrapPreservingAnsi("hello world", 8);
        assertEquals(List.of("hello", "world"), lines);
    }

    @Test
    void wrapPreservingAnsiHardBreaksTokenLongerThanTheLine() {
        // A single token with no space to break at still has to be split, or it would overflow the line width forever.
        var lines = AnsiWidth.wrapPreservingAnsi("supercalifragilistic", 8);
        assertTrue(lines.size() >= 3, "long unbreakable token is hard-wrapped: " + lines);
        for (String line : lines)
            assertTrue(AnsiWidth.visibleWidth(line) <= 8, "no row exceeds the width: " + line);
    }

    @Test
    void wrapPreservingAnsi_preservesNewlines() {
        var lines = AnsiWidth.wrapPreservingAnsi("line1\nline2", 80);
        assertEquals(2, lines.size());
    }

    @Test
    void wrapPreservingAnsi_handlesAnsiInsideText() {
        var text = "\033[31mred text\033[0m here";
        var lines = AnsiWidth.wrapPreservingAnsi(text, 10);
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains("\033"));
    }

    @Test
    void visibleWidthSurrogatePair() {
        assertEquals(2, AnsiWidth.visibleWidth("🙈"));
    }

    @Test
    void visibleWidthISOControlCharSkipped() {
        assertEquals(2, AnsiWidth.visibleWidth("a\bb"));
    }

    @Test
    void visibleWidthTabInMiddleExpands() {
        assertEquals(5, AnsiWidth.visibleWidth("a\tb"));
    }

    @Test
    void truncateVisualKeepsAnsiSequences() {
        var text = "\033[31mred text\033[0m";
        var result = AnsiWidth.truncateVisual(text, 4);
        assertTrue(result.startsWith("\033[31mred "));
        assertTrue(result.endsWith("\033[0m"));
    }

    @Test
    void truncateVisualCjk() {
        var result = AnsiWidth.truncateVisual("你好世界", 4);
        assertTrue(result.startsWith("你好"));
        assertTrue(result.endsWith("\033[0m"));
    }

    @Test
    void truncateVisualExactWidth() {
        // A string that exactly fits is returned unchanged — no cut, so no reset appended.
        assertEquals("hi", AnsiWidth.truncateVisual("hi", 2));
    }

    @Test
    void truncateVisualDropsWideGlyphCrossingTheBoundary() {
        // 你(2)好(2) fill width 5; 世(2) would make it 6 — it must be DROPPED, not kept: keeping it yields width 6 and
        // the terminal auto-wraps an extra physical row that desyncs frame bookkeeping.
        var result = AnsiWidth.truncateVisual("你好世界", 5);
        assertEquals("你好\033[0m", result);
        assertTrue(AnsiWidth.visibleWidth(result) <= 5);
    }

    @Test
    void wrapPreservingAnsiEmptyInput() {
        var lines = AnsiWidth.wrapPreservingAnsi("", 10);
        assertEquals(List.of(""), lines);
    }

    @Test
    void wrapPreservingAnsiTabBreaksLine() {
        var lines = AnsiWidth.wrapPreservingAnsi("a\tb", 5);
        assertFalse(lines.isEmpty());
    }

    @Test
    void wrapPreservingAnsiExpandsTabsToSpaces() {
        // Tabs must become spaces: a literal tab skips terminal cells without overwriting them, so stale content
        // (e.g. a separator left by a non-blanking erase) bleeds through tab-indented lines like Go's `\t\t}`.
        var lines = AnsiWidth.wrapPreservingAnsi("\t\t}", 80);
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("\t"));
        assertEquals("        }", lines.get(0)); // two tabs -> 8 spaces (4-col stops) + brace
    }

    @Test
    void stylingDoesNotMoveWrapPoints() {
        // The live preview is plain; the committed line is styled. Both wrap through wrapPreservingAnsi at the same
        // width, so committing must change only colors, never layout (no reflow). SGR sequences carry no visible width,
        // so the styled and plain forms must wrap to the same per-row visible widths.
        String plain = "the quick brown fox jumps over the lazy dog again and again";
        String styled = "the \033[1mquick\033[0m brown \033[3mfox\033[0m jumps over the \033[1mlazy\033[0m dog again and again";

        var plainRows = AnsiWidth.wrapPreservingAnsi(plain, 20);
        var styledRows = AnsiWidth.wrapPreservingAnsi(styled, 20);

        assertEquals(plainRows.size(), styledRows.size(), "same number of wrapped rows");
        for (int i = 0; i < plainRows.size(); i++) {
            assertEquals(AnsiWidth.visibleWidth(plainRows.get(i)), AnsiWidth.visibleWidth(styledRows.get(i)),
                                            "row " + i + " wraps at the same visible width with or without styling");
        }
    }

    @Test
    void wrapPreservingAnsiOsc8Link() {
        var text = "\033]8;;http://x.com\033\\link\033]8;;\033\\";
        var lines = AnsiWidth.wrapPreservingAnsi(text, 20);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("link"));
    }

    @Test
    void wrapPreservingAnsiSgrStateCarriesAcrossLines() {
        var text = "\033[31mred text here\033[0m";
        var lines = AnsiWidth.wrapPreservingAnsi(text, 5);
        assertTrue(lines.size() >= 2);
        assertTrue(lines.get(0).endsWith("\033[0m"));
        assertTrue(lines.get(1).contains("\033[31m"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Comprehensive wrap invariants. A wrapped row that exceeds the pane width makes the terminal auto-wrap an extra
    // physical row, which desyncs the append-only frame bookkeeping, so these properties are load-bearing, not nice
    // to have. Each is checked across a spread of realistic inputs (prose, styling, CJK, emoji, links, long tokens)
    // and every width, since the wrap point sweeps through every glyph as the width changes.
    // ------------------------------------------------------------------------------------------------------------

    /** A representative corpus that exercises every branch of the wrapper. */
    private static final List<String> WRAP_CORPUS = List.of(
                    "the quick brown fox jumps over the lazy dog again and again",
                    "a bb ccc dddd eeeee ffffff ggggggg hhhhhhhh",
                    "supercalifragilisticexpialidocious antidisestablishmentarianism",
                    "one really-long-hyphenated-token-with-no-spaces-at-all end",
                    "\033[1mbold\033[0m and \033[31mred\033[0m and \033[3;4mfancy\033[0m words wrapping here",
                    "你好 世界 这是 一个 换行 测试 文本 需要 处理 宽字符",
                    "mixed 你好world 123 \033[32mgreen你好\033[0m tail words here now",
                    "emoji 🙈 🙉 🙊 and 👨‍👩‍👧 families sprinkled 🚀 through the text",
                    "visit \033]8;;https://example.com/a/very/long/path\033\\the link text\033]8;;\033\\ then more words");

    private static String stripAnsi(String s) {
        // Drop OSC8 hyperlink wrappers (ESC ] 8 ; ... ESC \) then CSI/SGR sequences (ESC [ ... final-byte).
        return s.replaceAll("\033\\][^\033]*\033\\\\", "").replaceAll("\033\\[[0-9;]*[A-Za-z]", "");
    }

    @Test
    void noWrappedRowEverExceedsTheWidth() {
        for (String in : WRAP_CORPUS)
            for (int w = 2; w <= 40; w++)
                for (String row : AnsiWidth.wrapPreservingAnsi(in, w))
                    assertTrue(AnsiWidth.visibleWidth(row) <= w, "width " + w + ": row exceeds pane: <"
                                    + stripAnsi(row) + "> visible=" + AnsiWidth.visibleWidth(row) + " from: " + in);
    }

    @Test
    void wrappingPreservesEveryVisibleGlyphInOrder() {
        // Wrapping only ever drops the space it breaks at, never a printable glyph, never a duplicate. Comparing with
        // spaces removed sidesteps which spaces were consumed by soft breaks while still catching any lost/reordered char.
        for (String in : WRAP_CORPUS) {
            String expected = stripAnsi(in).replace(" ", "");
            for (int w = 2; w <= 40; w++) {
                StringBuilder joined = new StringBuilder();
                for (String row : AnsiWidth.wrapPreservingAnsi(in, w))
                    joined.append(stripAnsi(row));
                assertEquals(expected, joined.toString().replace(" ", ""),
                                "width " + w + " lost/duplicated a glyph for: " + in);
            }
        }
    }

    @Test
    void wrappingNeverSplitsAnAnsiEscapeSequence() {
        // Escapes are emitted atomically; a split one would leave a stray ESC that stripAnsi can't match.
        for (String in : WRAP_CORPUS)
            for (int w = 1; w <= 40; w++)
                for (String row : AnsiWidth.wrapPreservingAnsi(in, w))
                    assertTrue(stripAnsi(row).indexOf(0x1b) < 0, "width " + w + ": row splits an escape: " + row);
    }

    @Test
    void wrappingNeverSplitsASurrogatePair() {
        for (String in : WRAP_CORPUS)
            for (int w = 2; w <= 40; w++)
                for (String row : AnsiWidth.wrapPreservingAnsi(in, w)) {
                    String v = stripAnsi(row);
                    for (int i = 0; i < v.length(); i++) {
                        if (Character.isHighSurrogate(v.charAt(i)))
                            assertTrue(i + 1 < v.length() && Character.isLowSurrogate(v.charAt(i + 1)),
                                            "width " + w + ": dangling high surrogate in: " + v);
                        if (Character.isLowSurrogate(v.charAt(i)))
                            assertTrue(i > 0 && Character.isHighSurrogate(v.charAt(i - 1)),
                                            "width " + w + ": dangling low surrogate in: " + v);
                    }
                }
    }

    @Test
    void wrappingKeepsOsc8HyperlinksBalancedAndNeverBleeding() {
        // A hyperlink must open and close an even number of times across the whole wrap (never left dangling past the
        // output), and no row may end still inside a link; otherwise the link would extend into the row's blank gutter
        // and, worse, into the next frame. (A row may carry a harmless stray close for a link that opens later; a
        // close with no active link is a terminal no-op, so we count net balance rather than forbidding it.)
        String in = "before \033]8;;https://example.com/very/long/path\033\\clickable link words here\033]8;;\033\\ after tail";
        for (int w = 3; w <= 25; w++) {
            var rows = AnsiWidth.wrapPreservingAnsi(in, w);
            int total = 0;
            for (String row : rows) {
                for (int i = row.indexOf("\033]8;"); i >= 0; i = row.indexOf("\033]8;", i + 1))
                    total++;
                // the last OSC8 sequence on any row must be a close (empty URL), so the link never bleeds off the row
                int last = row.lastIndexOf("\033]8;");
                if (last >= 0)
                    assertTrue(row.startsWith("\033]8;;\033\\", last), "width " + w + ": row ends inside a link: "
                                    + stripAnsi(row));
            }
            assertEquals(0, total % 2, "width " + w + ": hyperlink opens/closes are unbalanced across the wrap");
        }
    }

    @Test
    void degenerateWidthOneDoesNotLoopOrThrow() {
        // Width 1 can't hold a width-2 glyph, but the wrapper must still terminate and place one glyph per row.
        for (String in : WRAP_CORPUS)
            assertFalse(AnsiWidth.wrapPreservingAnsi(in, 1).isEmpty(), "width 1 must still produce rows for: " + in);
    }

    @Test
    void styledWordCarriedToTheNextLineKeepsItsColor() {
        // A colored token split across a wrap must re-assert its color on the continuation and reset at the row end,
        // or the color leaks into the gutter / dies mid-word.
        var lines = AnsiWidth.wrapPreservingAnsi("\033[31mredword\033[0m", 4);
        assertTrue(lines.size() >= 2, lines.toString());
        assertTrue(lines.get(0).endsWith("\033[0m"), "row ends by resetting the active color: " + lines.get(0));
        assertTrue(lines.get(1).contains("\033[31m"), "continuation re-asserts the color: " + lines.get(1));
    }

    @Test
    void styledAndPlainWrapToIdenticalVisibleWidthsAcrossManyWidths() {
        String plain = "the quick brown fox jumps over the lazy dog once more with feeling";
        String styled = "the \033[1mquick\033[0m brown \033[31mfox\033[0m jumps over the \033[3mlazy\033[0m dog "
                        + "once more with feeling";
        for (int w = 4; w <= 30; w++) {
            var p = AnsiWidth.wrapPreservingAnsi(plain, w);
            var s = AnsiWidth.wrapPreservingAnsi(styled, w);
            assertEquals(p.size(), s.size(), "width " + w + ": styling changed the row count");
            for (int i = 0; i < p.size(); i++)
                assertEquals(AnsiWidth.visibleWidth(p.get(i)), AnsiWidth.visibleWidth(s.get(i)),
                                "width " + w + " row " + i + ": styling moved the wrap point");
        }
    }

    @Test
    void explicitNewlineResetsTheWordBreakState() {
        // A hard newline starts a fresh line; a break opportunity from the previous line must not leak across it.
        var lines = AnsiWidth.wrapPreservingAnsi("alpha beta\ngammadelta epsilon", 10);
        for (String row : lines)
            assertTrue(AnsiWidth.visibleWidth(row) <= 10, "row exceeds width: " + row);
        assertEquals("alpha beta", stripAnsi(lines.get(0)));
    }
}
