package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.PrintWriter;
import java.util.List;

public final class PromptLayout {

    private final List<PromptRegion> regions;
    private PromptSnapshot prompt = new PromptSnapshot(List.of(""), 0, 0, List.of());
    // The live streaming tail is previewed wrapped, but capped to its newest rows so a long paragraph can't overflow
    // (and break) the frame; the full text still lands in scrollback once the line commits.
    private static final int MAX_TAIL_ROWS = 6;

    private int renderedHeight;
    private int visualCursorRow;
    private int visualCursorCol;
    private int tailRows;

    public PromptLayout(List<PromptRegion> regions) {
        this.regions = List.copyOf(regions);
    }

    /**
     * Total rows every region of a type <em>other</em> than {@code excluded} would occupy for {@code snapshot}, plus
     * the one-row top separator. Used to budget a resizable region (the log viewer) so the whole frame fits on screen.
     */
    public int measuredHeightExcluding(Class<? extends PromptRegion> excluded, int width, PromptSnapshot snapshot) {
        int height = 1; // top separator
        for (var region : regions) {
            if (excluded.isInstance(region))
                continue;
            height += Math.max(0, region.height(width, snapshot));
        }
        return height;
    }

    /**
     * Moves to the top of the previously rendered region WITHOUT clearing it. The frame is then redrawn line by line,
     * each line overwritten then trimmed to its right (write-then-trim), and {@link #renderAfterErase} clears any
     * trailing rows at the very end. The screen is therefore never blanked before the redraw, so nothing strobes — even
     * on terminals that ignore synchronized output. (Callers that append committed lines wrap them to physical rows
     * first, so no terminal auto-wrap can leave stale content that a per-line clear would miss.)
     */
    public void eraseCurrent(PrintWriter out) {
        if (visualCursorRow > 0)
            out.print("\033[" + visualCursorRow + "A");
        out.print("\r");
    }

    public void renderAfterErase(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot snapshot) {
        prompt = snapshot;

        int height = 0;
        int cursorRow = -1;
        int cursorCol = -1;

        // The model's in-progress line, previewed live above the separator, wrapped across rows exactly as it will
        // appear once committed (capped to its newest MAX_TAIL_ROWS so a long paragraph can't overflow the frame). A
        // single-row tail is repainted in place per token (see canUpdateTailInPlace); a wrapped multi-row tail
        // re-renders via a pure repaint. Rendering it wrapped — rather than a horizontally scrolling single line —
        // is what keeps long in-progress lines readable.
        // A non-null tail (even "") reserves the live row for the whole streaming turn — kept at constant height so the
        // prompt doesn't jitter as lines commit; null means no streaming (idle), so no row. The tail arrives PRE-STYLED
        // (content normal, thinking dimmed), so it is laid out as-is, not re-styled here.
        String tail = snapshot.streamTail();
        tailRows = 0;
        if (tail != null) {
            var rows = AnsiWidth.wrapPreservingAnsi(tail, width);
            for (int r = Math.max(0, rows.size() - MAX_TAIL_ROWS); r < rows.size(); r++) {
                // write-then-trim (content, then \033[K) — never blank the row before redrawing it (see eraseCurrent).
                out.print("\r");
                out.print(rows.get(r));
                out.print("\033[K\r\n");
                height++;
                tailRows++;
            }
        }

        // A single quiet separator keeps the prompt visually grounded without boxing it in.
        out.print("\r");
        out.print(theme.styled("hr", "─".repeat(Math.max(10, width - 2))));
        out.print("\033[K\r\n");
        height++;
        int afterSeparator = height;

        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            int h = region.height(width, snapshot);
            if (h <= 0)
                continue;
            if (height > afterSeparator)
                out.print("\r\n");
            var cursor = region.render(out, width, theme, snapshot);
            if (cursor.visualRow() >= 0) {
                cursorRow = height + cursor.visualRow();
                cursorCol = cursor.visualCol();
            }
            height += h;
        }

        renderedHeight = Math.max(1, height);
        visualCursorRow = Math.max(0, cursorRow);
        visualCursorCol = Math.max(0, cursorCol);

        // The cursor sits at the bottom of the freshly drawn region; clear any rows a taller previous frame left below
        // it. This trims only stale rows beneath the new content (never anything just drawn), replacing the up-front
        // whole-region blank that would otherwise strobe.
        out.print("\033[J");

        if (cursorRow >= 0) {
            int rowsUp = renderedHeight - 1 - cursorRow;
            if (rowsUp > 0)
                out.print("\033[" + rowsUp + "A");
            out.print('\r');
            if (cursorCol > 0)
                out.print("\033[" + cursorCol + "C");
        }
        out.flush();
    }

    /**
     * Whether the live-tail can be repainted in place — only when the last full render drew it as a <em>single</em> row
     * (region row 0) above the input cursor. A multi-row (wrapped) tail changes the region geometry as it grows, so the
     * caller must do a full {@link #renderAfterErase} instead; likewise when no tail is shown yet.
     */
    public boolean canUpdateTailInPlace() {
        return tailRows == 1 && visualCursorRow >= 1;
    }

    /**
     * Repaints ONLY the live-tail row (region row 0) in place, leaving the separator/editor/status untouched, then
     * returns the cursor to the input position. The tail is truncated to a single row so it never wraps — clearing it
     * with one {@code \033[K} is safe. This is what makes high-frequency streaming repaints touch one line instead of
     * blanking and redrawing the whole region every token.
     */
    public void renderTailInPlace(PrintWriter out, int width, AnsiTheme theme, String tail) {
        // Write the new tail over the old, THEN trim any leftover to the right (\033[K). Clearing the line first
        // (\r\033[K then write) blanks it for an instant — at streaming speed that strobes on terminals without
        // synchronized output. Overwriting in place keeps the unchanged prefix steady and only the new suffix appears.
        out.print("\033[" + visualCursorRow + "A\r");
        out.print(AnsiWidth.truncateVisualEnd(tail == null ? "" : tail, width)); // pre-styled — laid out as-is
        out.print("\033[K");
        out.print("\r\033[" + visualCursorRow + "B");
        if (visualCursorCol > 0)
            out.print("\033[" + visualCursorCol + "C");
        out.flush();
    }

}
