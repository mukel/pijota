package org.pijota.tui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptLayoutTest {

    /** A region of fixed height used to make the budget arithmetic explicit. */
    private record FixedRegion(int rows) implements PromptRegion.Custom {
        @Override
        public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
            return Body.of(Collections.nCopies(rows, ""));
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
        assertEquals(2 + 1 + 4, layout.measuredHeightExcluding(LogViewerRegion.class, 80, AnsiTheme.dark(),
                                        snapshot()));
    }

    @Test
    void promptRulesSpanTheFullTerminalWidth() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        String rendered = render(layout, 20, snapshot());

        var lines = rendered.lines().filter(line -> line.indexOf('─') >= 0).toList();
        assertEquals(2, lines.size(), rendered);
        assertTrue(lines.stream().allMatch(line -> AnsiWidth.visibleWidth(line) == 20), rendered);
    }

    @Test
    void promptRulesNeverExceedTheTerminalWidth() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor()));

        String rendered = render(layout, 4, snapshot());

        // A rule wider than the terminal would auto-wrap onto an extra physical row the frame never composed — the
        // classic source of a one-row drift on every redraw. Even absurdly narrow terminals get exact-width rules.
        var lines = rendered.lines().filter(line -> line.indexOf('─') >= 0).toList();
        assertEquals(2, lines.size(), rendered);
        assertTrue(lines.stream().allMatch(line -> AnsiWidth.visibleWidth(line) == 4), rendered);
    }

    @Test
    void excludedRegionDoesNotCountEvenWhenActive() {
        var lv = new LogViewer();
        lv.show("t", List.of("a", "b", "c"));
        lv.setViewport(20);
        var viewer = new LogViewerRegion(lv);
        var layout = new PromptLayout(List.of(new FixedRegion(2), viewer));

        // Only the FixedRegion (2) + separator (1) are counted; the active viewer is skipped entirely.
        assertEquals(3, layout.measuredHeightExcluding(LogViewerRegion.class, 80, AnsiTheme.dark(), snapshot()));
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

    /** Rows {@link PromptLayout#eraseCurrent} walks up — i.e. how far above the parked cursor the frame top sits. */
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

    @Test
    void eraseWalksUpExactlyFromTheParkedCaretRowNeverTheWholeFrame() {
        var layout = new PromptLayout(List.of(new PromptRegion.Editor(), new PromptRegion.StatusBar()));
        var snap = snapshot().withCwd("/workspace").withStatus("tokens and model");

        render(layout, 80, snap);

        // Frame rows: top rule, editor input, editor bottom rule, cwd, status — and the cursor parks on the editor
        // input (row 1). Erase must walk up exactly that far: walking the whole frame height instead would climb past
        // the frame top into committed scrollback and overwrite it on EVERY redraw (the eaten-lines regression).
        assertEquals(1, eraseUpRows(layout));
    }

    @Test
    void frameTallerThanTheScreenIsClampedKeepingThePromptRows() {
        var layout = new PromptLayout(List.of(new FixedRegion(30), new PromptRegion.Editor()));

        var sw = new StringWriter();
        layout.renderAfterErase(new PrintWriter(sw), 80, 10, AnsiTheme.dark(), snapshot());
        String rendered = AnsiStripper.strip(sw.toString());
        int erase = eraseUpRows(layout);

        // 30 filler rows + separator + editor can't fit a 10-row screen: the frame keeps its BOTTOM 10 rows (the
        // prompt), because rows above the screen top are unreachable by relative cursor moves — an unclamped frame
        // loses its anchor and drifts on every redraw. Rows are separated by \r\n, so 10 rows = 9 separators.
        assertTrue(sw.toString().split("\r\n", -1).length <= 10, rendered);
        assertTrue(erase <= 9, "parked cursor must stay within the on-screen frame, was " + erase + " rows up");
        assertTrue(rendered.contains("─"), "the prompt (bottom of the frame) survives the clamp");
    }

    @Test
    void customRegionRowsWithLineBreaksAreSanitizedNotEmittedRaw() {
        PromptRegion.Custom hostile = (width, theme, ctx) -> PromptRegion.Body.of(List.of("bad\r\nrow"));
        var layout = new PromptLayout(List.of(hostile), false);

        var sw = new StringWriter();
        layout.renderAfterErase(new PrintWriter(sw), 80, TALL, AnsiTheme.dark(), snapshot());

        // One composed row must occupy exactly one physical row: a raw \n would create an extra row the frame's
        // bookkeeping never saw. The layout's emission choke point flattens it.
        assertEquals(0, eraseUpRows(layout), "a single-row frame parks its cursor on its only row");
        assertFalse(AnsiStripper.strip(sw.toString()).contains("bad\r\nrow"), sw.toString());
    }
}
