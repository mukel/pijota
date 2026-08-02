package org.pijota.tui;

import org.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptRegionTest {
    private static final AnsiTheme THEME = AnsiTheme.dark();

    @Test
    void editorRendersSuggestionWhenCursorAtEnd() {
        var region = new PromptRegion.Editor();
        var snapshot = new PromptSnapshot(List.of("/se"), 0, 3, List.of()).withSuggestion("/sessions");

        String rendered = String.join("\n", region.compose(80, THEME, snapshot).rows());

        assertTrue(rendered.contains("/se"));
        assertTrue(rendered.contains("/sessions"));
    }

    @Test
    void editorDoesNotRenderSuggestionWhenCursorNotAtEnd() {
        var region = new PromptRegion.Editor();
        var snapshot = new PromptSnapshot(List.of("/se"), 0, 2, List.of()).withSuggestion("/sessions");

        String rendered = String.join("\n", region.compose(80, THEME, snapshot).rows());

        assertTrue(rendered.contains("/se"));
        assertFalse(rendered.contains("/sessions"));
    }

    @Test
    void completionRowsNeverExceedWidthSoFrameHeightStaysAccurate() {
        // A row wider than the terminal auto-wraps to 2 physical rows the frame never composed; that mismatch desyncs
        // PromptLayout and stacks stale separators. Every composed row must stay within width.
        var region = new PromptRegion.Completions();
        int width = 40;
        var items = List.of(new CompletionItem("/a-very-long-command-name-that-overflows-the-line-by-a-lot",
                                        "and a description that is itself far longer than the terminal is wide"),
                                        new CompletionItem("/short", "brief"));
        var snapshot = new PromptSnapshot(List.of("/a"), 0, 2, items, 0);

        var rows = region.compose(width, THEME, snapshot).rows();

        for (String row : rows)
            assertTrue(AnsiWidth.visibleWidth(row) <= width, "row exceeds width " + width + " ("
                                            + AnsiWidth.visibleWidth(row) + "): " + row);
        assertEquals(1 + items.size() + 1, rows.size(), "header + one row per item + footer");
    }

    @Test
    void editorGhostSuggestionNeverExceedsWidth() {
        // A long history suggestion, left unclipped, wraps the dim ghost onto a 2nd physical row and corrupts the
        // prompt's cursor/layout bookkeeping. Every composed row must stay within width.
        var region = new PromptRegion.Editor();
        int width = 20;
        var snapshot = new PromptSnapshot(List.of("hi"), 0, 2, List.of()).withSuggestion("x".repeat(200));

        for (String row : region.compose(width, THEME, snapshot).rows())
            assertTrue(AnsiWidth.visibleWidth(row) <= width, "prompt row exceeds width " + width + " ("
                                            + AnsiWidth.visibleWidth(row) + "): " + row);
    }

    @Test
    void editorWrapsWideGlyphsByColumnsNotChars() {
        // 20 CJK chars are 40 visible columns. A char-counted wrap would put up to 2× the terminal width on one row
        // and the terminal would auto-wrap it, an extra physical row the frame's bookkeeping never sees, shifting the
        // redraw by one on every keystroke. Column-accurate wrapping must keep every row within width.
        var region = new PromptRegion.Editor();
        int width = 24;
        String cjk = "漢".repeat(20);
        var snapshot = new PromptSnapshot(List.of(cjk), 0, cjk.length(), List.of());

        var body = region.compose(width, THEME, snapshot);

        for (String row : body.rows())
            assertTrue(AnsiWidth.visibleWidth(row) <= width, "wide-glyph row exceeds width " + width + " ("
                                            + AnsiWidth.visibleWidth(row) + "): " + row);
        assertTrue(body.rows().size() >= 3, "40 columns of text at width 24 must wrap to multiple rows");
    }

    @Test
    void editorCaretColumnCountsWideGlyphsAsTwoCells() {
        var region = new PromptRegion.Editor();
        var snapshot = new PromptSnapshot(List.of("漢漢ab"), 0, 3, List.of()); // caret after 漢漢a

        var body = region.compose(80, THEME, snapshot);

        // Caret column is measured in visible cells: prefix + 2+2+1. A char-counted caret would sit 2 cells short.
        int prefixW = AnsiWidth.visibleWidth(body.rows().get(0)) - AnsiWidth.visibleWidth("漢漢ab");
        assertEquals(prefixW + 5, body.cursorCol());
    }

    @Test
    void ghostTextClipsToFirstLineAndWidth() {
        assertEquals("llo world", PromptRegion.ghostText("llo world", 20)); // fits → unchanged
        String clipped = PromptRegion.ghostText("llo world", 3);
        assertTrue(clipped.startsWith("ll"), clipped);
        assertTrue(clipped.endsWith("…"), "a clipped ghost says so, even when the room is tiny: " + clipped);
        assertTrue(AnsiWidth.visibleWidth(clipped) <= 3);
        assertEquals("abc", PromptRegion.ghostText("abc\ndef", 10)); // first line only, fits → unchanged
        assertEquals("", PromptRegion.ghostText("anything", 0)); // no room
        assertEquals("", PromptRegion.ghostText(null, 5));
    }

    @Test
    void aGhostThatFitsIsShownWhole() {
        assertEquals("run the tests", PromptRegion.ghostText("run the tests", 20));
        assertEquals("run the tests", PromptRegion.ghostText("run the tests", 13), "exactly fitting is not clipped");
    }

    /**
     * Without a cue, a clipped suggestion reads as a finished one. Tab still accepts the full text -- the clip is
     * display only -- so the ellipsis is what tells the user the rest exists.
     */
    @Test
    void aClippedGhostSaysThereIsMore() {
        String ghost = PromptRegion.ghostText("explain what divide does in one short sentence", 20);
        assertEquals(20, AnsiWidth.visibleWidth(ghost), "never wider than the room it was given");
        assertTrue(ghost.endsWith("…"), "the cue that there is more: " + ghost);
    }

    @Test
    void aGhostWithNoRoomDegradesRatherThanOverflowing() {
        assertEquals("", PromptRegion.ghostText("anything", 0));
        assertEquals("…", PromptRegion.ghostText("anything", 1));
    }

    @Test
    void onlyTheFirstLineOfAGhostIsEverDrawn() {
        assertEquals("first", PromptRegion.ghostText("first\nsecond", 20), "a newline would break the frame");
    }

}
