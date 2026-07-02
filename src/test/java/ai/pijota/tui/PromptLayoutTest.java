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
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
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

        // Editor is 2 rows (empty input line + bottom rule) + FixedRegion 4 + the 1-row separator = 7. Viewer excluded.
        assertEquals(2 + 1 + 4, layout.measuredHeightExcluding(LogViewerRegion.class, 80, snapshot()));
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

    private static final int TALL = 100; // a terminal tall enough that the tail budget never truncates

    private static String render(PromptLayout layout, int width, PromptSnapshot snap) {
        return render(layout, width, TALL, snap);
    }

    private static String render(PromptLayout layout, int width, int height, PromptSnapshot snap) {
        var sw = new StringWriter();
        layout.renderAfterErase(new PrintWriter(sw), width, height, AnsiTheme.dark(), snap);
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
    void longStreamTailWrapsToManyRowsWhenScreenAllows() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        render(layout, 40, TALL, snapshot());
        int plainRows = eraseUpRows(layout);
        render(layout, 40, TALL, snapshot().withStreamTail("x".repeat(500)));
        int longRows = eraseUpRows(layout);
        // 500 cols at width 40 wraps to 13 rows; the tall screen has room, so the whole in-progress block is shown.
        assertEquals(plainRows + 13, longRows, "a long in-progress line streams in full when the screen has room");
    }

    @Test
    void longStreamTailCappedToAvailableScreenRowsKeepingNewest() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        // height 10: separator (1) + editor (2: input + bottom rule) + one spare row leaves a 6-row budget for the
        // tail.
        render(layout, 40, 10, snapshot());
        int plainRows = eraseUpRows(layout);
        render(layout, 40, 10, snapshot().withStreamTail("x".repeat(500)));
        int longRows = eraseUpRows(layout);
        // 13 wrapped rows can't fit; capped to the newest 6 so it never pushes the prompt off the bottom.
        assertEquals(plainRows + 6, longRows, "a tail taller than the screen is capped to the newest rows");
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
        layout.renderAfterErase(new PrintWriter(sw), 80, TALL, AnsiTheme.dark(), snapshot());
        assertTrue(sw.toString().contains("\033[J"), "redraw trims leftover rows of a shrunk frame at the end");
    }
}
