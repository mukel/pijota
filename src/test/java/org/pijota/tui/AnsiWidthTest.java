package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnsiWidthTest {

    /**
     * Util.ellipsize counts characters, which equals columns only for the Latin alphabet. A CJK title cut to 40
     * "characters" is 80 columns, and the launch banner's session rows wrapped onto a second line, splitting their
     * metadata away from their title.
     */
    @Test
    void ellipsizeCutsByColumnsNotCharacters() {
        String cjk = "\u65e5\u672c\u8a9e\u306e\u30c6\u30ad\u30b9\u30c8"; // 8 chars, 16 columns

        assertEquals(cjk, AnsiWidth.ellipsize(cjk, 16), "it fits exactly and must not be cut");
        assertTrue(AnsiWidth.visibleWidth(AnsiWidth.ellipsize(cjk, 10)) <= 10, "cut result still overflowed");
        assertTrue(AnsiWidth.ellipsize(cjk, 10).endsWith("\u2026"), "a cut must be marked");

        // never splits a character in half
        assertEquals(AnsiWidth.visibleWidth(AnsiWidth.ellipsize(cjk, 7)) % 2, 1, "odd budget leaves the ellipsis");

        assertEquals("abcd\u2026", AnsiWidth.ellipsize("abcdefgh", 5), "plain ASCII behaves as before");
        assertEquals("", AnsiWidth.ellipsize(null, 5));
    }

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
        // "hello world" at width 8 must wrap at the space (-> "hello" / "world"), not slice the word ("hello wo" /
        // "rld").
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
        // A string that exactly fits is returned unchanged: no cut, so no reset appended.
        assertEquals("hi", AnsiWidth.truncateVisual("hi", 2));
    }

    @Test
    void truncateVisualDropsWideGlyphCrossingTheBoundary() {
        // 你(2)好(2) fill width 5; 世(2) would make it 6, so it must be DROPPED, not kept: keeping it yields width 6 and
        // the terminal auto-wraps an extra physical row that desyncs frame bookkeeping.
        var result = AnsiWidth.truncateVisual("你好世界", 5);
        assertEquals("你好\033[0m", result);
        assertTrue(AnsiWidth.visibleWidth(result) <= 5);
    }

    @Test
    void truncateMiddleKeepsBothEndsOfAPath() {
        // The workspace line must stay one row: a deep path keeps its root AND the project/branch tail that
        // identifies it, unlike an end-truncation which would drop exactly the useful part.
        String path = "/home/user/work/monorepo/packages/service/src/main (feature-branch)";
        String out = AnsiWidth.truncateMiddle(path, 40);
        assertTrue(AnsiWidth.visibleWidth(out) <= 40, "fits the width: " + out);
        assertTrue(out.startsWith("/home"), "root kept: " + out);
        assertTrue(out.endsWith("(feature-branch)"), "tail kept: " + out);
        assertTrue(out.contains("…"), "elision marked: " + out);
    }

    @Test
    void truncateMiddleLeavesFittingTextAlone() {
        assertEquals("~/proj (main)", AnsiWidth.truncateMiddle("~/proj (main)", 40));
        assertEquals("", AnsiWidth.truncateMiddle("anything", 0)); // no room at all
        assertEquals("…", AnsiWidth.truncateMiddle("anything", 1)); // room only for the marker
    }

    @Test
    void truncateMiddleNeverSplitsAWideGlyph() {
        String cjk = "/项目/很长的路径名称/子目录/源代码/主要 (分支)";
        for (int w = 2; w <= 30; w++) {
            String out = AnsiWidth.truncateMiddle(cjk, w);
            assertTrue(AnsiWidth.visibleWidth(out) <= w, "width " + w + " overflows: " + out);
            for (int i = 0; i < out.length(); i++)
                if (Character.isHighSurrogate(out.charAt(i)))
                    assertTrue(i + 1 < out.length() && Character.isLowSurrogate(out.charAt(i + 1)),
                                                    "width " + w + ": split surrogate in " + out);
        }
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
                    assertTrue(AnsiWidth.visibleWidth(row) <= w, "width " + w + ": row exceeds pane: <" + stripAnsi(row)
                                                    + "> visible=" + AnsiWidth.visibleWidth(row) + " from: " + in);
    }

    @Test
    void wrappingPreservesEveryVisibleGlyphInOrder() {
        // Wrapping only ever drops the space it breaks at, never a printable glyph, never a duplicate. Comparing with
        // spaces removed sidesteps which spaces were consumed by soft breaks while still catching any lost/reordered
        // char.
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
                    assertTrue(row.startsWith("\033]8;;\033\\", last),
                                                    "width " + w + ": row ends inside a link: " + stripAnsi(row));
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

    /**
     * A variation selector, a joiner, a skin-tone modifier and a combining accent all modify the character before them
     * and take no cell of their own. U+FE0F was counted as one, which is what a user saw: in a rendered markdown table
     * a cell holding {@code \u26a0\ufe0f} measured 3 where the terminal drew 2, so it was padded one space short and
     * its column separator sat a column left of every other row's.
     */
    @Test
    void codepointsThatModifyTheCharacterBeforeThemTakeNoCell() {
        assertEquals(0, AnsiWidth.visibleWidth("\ufe0f"), "variation selector-16");
        assertEquals(0, AnsiWidth.visibleWidth("\u200d"), "zero-width joiner");
        assertEquals(2, AnsiWidth.visibleWidth("\u26a0\ufe0f"), "the warning sign the table row was misaligned by");
        assertEquals(1, AnsiWidth.visibleWidth("e\u0301"), "e + combining acute is one cell, not two");
        assertEquals(2, AnsiWidth.visibleWidth("\ud83d\udc4d\ud83c\udffd"), "thumbs up + skin tone is one glyph");
    }

    /** U+FE0F asks for the emoji rendering of the character before it, which is always the two-cell one. */
    @Test
    void anEmojiPresentationSelectorWidensANarrowBase() {
        assertEquals(1, AnsiWidth.visibleWidth("\u2194"), "the arrow alone is text presentation, one cell");
        assertEquals(2, AnsiWidth.visibleWidth("\u2194\ufe0f"), "with the selector the terminal draws the emoji");
    }

    /** The defect as it was reported: two table cells that must pad to the same column. */
    @Test
    void emojiCellsOfTheSameGlyphCountMeasureTheSame() {
        assertEquals(AnsiWidth.visibleWidth("\u2705 works"), AnsiWidth.visibleWidth("\u26a0\ufe0f works"),
                                        "one emoji plus the same word must occupy the same columns");
    }

    /**
     * A joined emoji is one glyph however many emoji it is spelled with, so it claims one glyph's cells. Measured per
     * codepoint it came out as the sum of its parts: {@code 👨‍💻} four cells, the four-person family eight.
     *
     * <p>
     * Every expectation here was checked against the terminal rather than assumed, by printing the glyph into a tmux
     * pane and reading the cursor column back.
     */
    @Test
    void aJoinedEmojiIsOneGlyphAndClaimsOneGlyphsCells() {
        assertEquals(2, AnsiWidth.visibleWidth("\ud83d\udc68\u200d\ud83d\udcbb"), "man technologist");
        assertEquals(2, AnsiWidth.visibleWidth("\ud83d\udc69\ud83c\udffd\u200d\ud83d\udcbb"),
                                        "woman technologist: skin tone and a join in the same cluster");
        assertEquals(2, AnsiWidth.visibleWidth("\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66"),
                                        "family: four emoji, three joins, still one glyph");
        assertEquals(2, AnsiWidth.visibleWidth("\u2764\ufe0f\u200d\ud83d\udd25"),
                                        "heart on fire: a selector and a join together");
    }

    /** A flag is a pair of regional indicators; each pair is one glyph, and the pairing restarts after other text. */
    @Test
    void flagsAreCountedInPairs() {
        assertEquals(2, AnsiWidth.visibleWidth("\ud83c\udde9\ud83c\uddea"), "one flag");
        assertEquals(4, AnsiWidth.visibleWidth("\ud83c\udde9\ud83c\uddea\ud83c\uddeb\ud83c\uddf7"), "two flags");
        assertEquals(5, AnsiWidth.visibleWidth("\ud83c\udde9\ud83c\uddeax\ud83c\uddeb\ud83c\uddf7"),
                                        "text between them restarts the pairing rather than shifting it");
    }

    /** A keycap is a digit, a selector and an enclosing mark: one glyph, two cells. */
    @Test
    void aKeycapIsOneGlyph() {
        assertEquals(2, AnsiWidth.visibleWidth("1\ufe0f\u20e3"));
    }

    /**
     * Wrapping must not slice a cluster: the tail codepoints measure zero, so once the base fits they all fit, and a
     * row can never end with a dangling joiner whose emoji went to the next line.
     */
    @Test
    void wrappingKeepsAJoinedEmojiWhole() {
        String emoji = "\ud83d\udc68\u200d\ud83d\udcbb";
        for (String row : AnsiWidth.wrapPreservingAnsi("aa " + emoji + " bb", 5))
            assertFalse(row.endsWith("\u200d"), "row ends on a joiner, so its glyph was split: " + row);
        assertEquals(emoji, AnsiWidth.truncateVisual(emoji, 2), "a cluster that fits is kept entire");
        assertEquals(0, AnsiWidth.visibleWidth(AnsiWidth.truncateVisual(emoji, 1)),
                                        "and one that does not is dropped entire, not cut in half");
    }

    /**
     * pijota's own mark language, measured against what a terminal actually draws. The symbol blocks hold both genuine
     * two-cell emoji and ambiguous marks drawn in one, and the hand-drawn range boundary called all of them wide -- so
     * every tool line, every rail and every selector row carrying one of these was measured a column wider than it
     * rendered. Unicode's Emoji_Presentation property is the boundary; each of these was checked by printing it into a
     * tmux pane and reading the cursor column back.
     */
    @Test
    void everyMarkTheInterfaceDrawsIsMeasuredAsTheTerminalDrawsIt() {
        for (String oneCell : List.of("●", "○", "✖", "✓", "◆", "▸", "⏸", "■", "❯", "➜", "│", "└", "┌", "─", "⋯", "…",
                                        "→", "▶", "★", "⬤"))
            assertEquals(1, AnsiWidth.visibleWidth(oneCell), oneCell + " is one cell in a terminal");
        for (String twoCells : List.of("✅", "🙈", "你"))
            assertEquals(2, AnsiWidth.visibleWidth(twoCells), twoCells + " really is two");
    }

    /** The rule, not the list: within the symbol blocks, an emoji is wide and everything else is not. */
    @Test
    void withinTheSymbolBlocksOnlyEmojiAreWide() {
        for (int cp = 0x2300; cp <= 0x27BF; cp++) {
            String s = new String(Character.toChars(cp));
            assertEquals(Character.isEmojiPresentation(cp) ? 2 : 1, AnsiWidth.visibleWidth(s),
                                            () -> String.format("U+%04X", s.codePointAt(0)));
        }
    }
}
