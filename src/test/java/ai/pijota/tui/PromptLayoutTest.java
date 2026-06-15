package ai.pijota.tui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Pattern;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptLayoutTest {

    /** A region of fixed height used to make the budget arithmetic explicit. */
    private record FixedRegion(int rows) implements PromptRegion.Custom {
        @Override
        public int height(int width, PromptSnapshot ctx) {
            return rows;
        }

        @Override
        public CursorPos render(java.io.PrintWriter out, int width, ai.pijota.theme.AnsiTheme theme,
                                        PromptSnapshot ctx) {
            return NO_CURSOR;
        }
    }

    private static PromptSnapshot snapshot() {
        return new PromptSnapshot(List.of(""), 0, 0, List.of());
    }

    @Test
    void measuredHeightExcludingSumsEveryOtherRegionPlusSeparator() {
        var viewer = new LogViewerRegion(new LogViewer());
        var layout = new PromptLayout(List.of(new PromptRegion.Editor(), new FixedRegion(4), viewer));

        // Editor is 1 row (empty line) + FixedRegion 4 + the 1-row separator = 6. The viewer is excluded.
        assertEquals(1 + 1 + 4, layout.measuredHeightExcluding(LogViewerRegion.class, 80, snapshot()));
    }

    @Test
    void excludedRegionDoesNotCountEvenWhenActive() {
        var lv = new LogViewer();
        lv.show("t", List.of("a", "b", "c"));
        lv.setViewport(20);
        var viewer = new LogViewerRegion(lv);
        var layout = new PromptLayout(List.of(new FixedRegion(2), viewer));

        // Only the FixedRegion (2) + separator (1) are counted; the active viewer is skipped entirely.
        assertEquals(3, layout.measuredHeightExcluding(LogViewerRegion.class, 80, snapshot()));
    }

    private static final Pattern CURSOR_UP = Pattern.compile("\\u001B\\[(\\d+)A");

    private static String render(PromptLayout layout, int width, PromptSnapshot snap) {
        var sw = new StringWriter();
        layout.renderAfterErase(new PrintWriter(sw), width, AnsiTheme.dark(), snap);
        return AnsiStripper.strip(sw.toString());
    }

    /** Rows {@link PromptLayout#eraseCurrent} walks up — i.e. the controlled-region height it will reclaim. */
    private static int eraseUpRows(PromptLayout layout) {
        var sw = new StringWriter();
        layout.eraseCurrent(new PrintWriter(sw));
        var m = CURSOR_UP.matcher(sw.toString());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Test
    void streamTailRendersAboveSeparatorAndGrowsEraseHeight() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        String plain = render(layout, 80, snapshot());
        int erasePlain = eraseUpRows(layout);
        assertFalse(plain.contains("streaming tail"), plain);

        String tailed = render(layout, 80, snapshot().withStreamTail("streaming tail"));
        int eraseTailed = eraseUpRows(layout);
        assertTrue(tailed.contains("streaming tail"), tailed);
        assertEquals(erasePlain + 1, eraseTailed, "the live tail adds exactly one controlled row");
    }

    @Test
    void nullTailHasNoRowButReservedEmptyTailKeepsOne() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        render(layout, 80, snapshot()); // null tail = idle, no live row
        int noTail = eraseUpRows(layout);
        render(layout, 80, snapshot().withStreamTail("")); // reserved (non-null) row during streaming, even when empty
        int reserved = eraseUpRows(layout);
        assertEquals(noTail + 1, reserved, "a reserved tail adds exactly one row, even when empty");
    }

    @Test
    void longStreamTailWrapsCappedToMaxRows() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        render(layout, 80, snapshot());
        int plainRows = eraseUpRows(layout);
        render(layout, 40, snapshot().withStreamTail("x".repeat(500)));
        int longRows = eraseUpRows(layout);
        // 500 cols at width 40 wraps to 13 rows, capped to the newest MAX_TAIL_ROWS (6)
        assertEquals(plainRows + 6, longRows, "a long tail wraps but is capped so it can't overflow the frame");
        assertFalse(layout.canUpdateTailInPlace(), "a wrapped multi-row tail cannot be updated in place");
    }

    @Test
    void eraseNeverBlanksTheRegionUpFront() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));
        render(layout, 80, snapshot());

        var sw = new StringWriter();
        layout.eraseCurrent(new PrintWriter(sw));
        // No \033[J before the redraw — the region is overwritten line-by-line, never blanked first (no strobe).
        assertFalse(sw.toString().contains("\033[J"), sw.toString());
    }

    @Test
    void renderTrimsTrailingRowsAtTheEnd() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));
        var sw = new StringWriter();
        layout.renderAfterErase(new PrintWriter(sw), 80, AnsiTheme.dark(), snapshot());
        assertTrue(sw.toString().contains("\033[J"), "redraw trims leftover rows of a shrunk frame at the end");
    }

    @Test
    void canUpdateTailInPlaceOnlyAfterATailRowIsDrawn() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        render(layout, 80, snapshot());
        assertFalse(layout.canUpdateTailInPlace(), "no tail row drawn yet");

        render(layout, 80, snapshot().withStreamTail("hi"));
        assertTrue(layout.canUpdateTailInPlace(), "a tail row exists, so it can be repainted in place");

        var sw = new StringWriter();
        layout.renderTailInPlace(new PrintWriter(sw), 80, AnsiTheme.dark(), "hello");
        String out = sw.toString();
        assertTrue(out.contains("hello"), out);
        assertTrue(out.contains("\033[K"), "the tail row is cleared before rewriting");
        assertFalse(out.contains("\033[J"), "in-place tail must not blank anything");
    }
}
