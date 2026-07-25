package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.PrintWriter;
import java.util.List;

public final class PromptLayout {

    private final List<PromptRegion> regions;
    private final boolean topDivider;
    private PromptSnapshot prompt = new PromptSnapshot(List.of(""), 0, 0, List.of());

    private int renderedHeight;
    private int visualCursorRow;
    private int visualCursorCol;

    public PromptLayout(List<PromptRegion> regions) {
        this(regions, true);
    }

    public PromptLayout(List<PromptRegion> regions, boolean topDivider) {
        this.regions = List.copyOf(regions);
        this.topDivider = topDivider;
    }

    /**
     * Total rows every region of a type <em>other</em> than {@code excluded} would occupy for {@code snapshot}, plus
     * the one-row top separator. Used to budget a resizable region (the log viewer) so the whole frame fits on screen.
     */
    public int measuredHeightExcluding(Class<? extends PromptRegion> excluded, int width, PromptSnapshot snapshot) {
        int height = topDivider ? 1 : 0; // top separator (optional)
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

    public void renderAfterErase(PrintWriter out, int width, int terminalHeight, AnsiTheme theme,
                                    PromptSnapshot snapshot) {
        prompt = snapshot;

        int height = 0;
        int cursorRow = -1;
        int cursorCol = -1;

        // Measure every region once; the tail budget and the render loop below both need these heights.
        int[] regionHeights = new int[regions.size()];
        int regionsTotal = 0;
        for (int i = 0; i < regions.size(); i++) {
            regionHeights[i] = Math.max(0, regions.get(i).height(width, snapshot));
            regionsTotal += regionHeights[i];
        }

        // The in-progress line is previewed live above the separator, wrapped to physical rows by the SAME routine that
        // wraps committed lines (wrapPreservingAnsi) and at the same width — so styling, which never changes a glyph's
        // visible width, can't move a wrap point: committing the line changes only colors, never layout (no reflow).
        // The preview is capped to the rows left on screen after the separator + prompt regions, keeping the NEWEST
        // rows, so a long in-progress block streams in full yet can never push the prompt off the bottom. A non-null
        // tail (even "") reserves the row(s) for the streaming turn so the prompt doesn't jitter as lines commit; null
        // means idle (no preview). The tail arrives PRE-STYLED with the base slot only (content normal, thinking
        // dimmed); inline markdown styling and syntax highlighting are deferred to commit.
        String tail = snapshot.streamTail();
        if (tail != null) {
            int tailBudget = Math.max(1, terminalHeight - regionsTotal - 2); // less separator + one spare row
            var rows = AnsiWidth.wrapPreservingAnsi(tail, width);
            for (int r = Math.max(0, rows.size() - tailBudget); r < rows.size(); r++) {
                // write-then-trim (content, then \033[K) — never blank the row before redrawing it (see eraseCurrent).
                out.print("\r");
                out.print(rows.get(r));
                out.print("\033[K\r\n");
                height++;
            }
        }

        // Top rule of the prompt frame (the Editor draws the matching bottom rule) — a clean frame without a box; the
        // in-box caret carries state, so the rule stays plain. Tinted by state (red on error). Optional (top_divider).
        if (topDivider) {
            out.print("\r");
            out.print(theme.styled(PromptRegion.ruleSlot(snapshot), PromptRegion.rule(width)));
            out.print("\033[K\r\n");
            height++;
        }
        int afterSeparator = height;

        for (int i = 0; i < regions.size(); i++) {
            int h = regionHeights[i];
            if (h <= 0)
                continue;
            if (height > afterSeparator)
                out.print("\r\n");
            var cursor = regions.get(i).render(out, width, theme, snapshot);
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

}
