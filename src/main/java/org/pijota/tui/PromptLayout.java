package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Composes and emits the prompt frame (stream tail, divider, regions) as a flat list of physical rows.
 *
 * <p>
 * The frame's height is <em>defined</em> as the number of composed rows. There is no separate measurement that could
 * disagree with what was drawn. Every row is clamped to the terminal width before emission and the renderer disables
 * terminal auto-wrap around the frame, so the terminal can never materialise more rows than were composed. Together
 * with the {@link Stamp} below this makes redraw drift structurally impossible: at worst a single frame is misdrawn
 * (e.g. the terminal reflowed the previous frame on resize), and the very next frame re-anchors from fresh truth.
 */
public final class PromptLayout {

    private final List<PromptRegion> regions;
    private final boolean topDivider;

    /**
     * Where the previous frame left the terminal: how many rows it occupies and on which of them the cursor was parked.
     * Erase distance and park position are one value written by one method, so they cannot diverge (the
     * eaten-scrollback regression fixed here was exactly these two numbers being maintained independently).
     */
    private record Stamp(int rows, int cursorRowFromTop) {
        static final Stamp NONE = new Stamp(1, 0);
    }

    private Stamp stamp = Stamp.NONE;

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
    public int measuredHeightExcluding(Class<? extends PromptRegion> excluded, int width, AnsiTheme theme,
                                    PromptSnapshot snapshot) {
        int height = topDivider ? 1 : 0; // top separator (optional)
        for (var region : regions) {
            if (excluded.isInstance(region))
                continue;
            height += region.compose(width, theme, snapshot).rows().size();
        }
        return height;
    }

    /**
     * Moves to the top of the previously rendered frame WITHOUT clearing it. The frame is then redrawn line by line,
     * each line overwritten then trimmed to its right (write-then-trim), and {@link #renderAfterErase} clears any
     * trailing rows at the very end. The screen is therefore never blanked before the redraw, so nothing strobes, even
     * on terminals that ignore synchronized output. (Callers that append committed lines wrap them to physical rows
     * first, so no terminal auto-wrap can leave stale content that a per-line clear would miss.)
     *
     * <p>
     * The cursor is parked on the caret row, not the frame's last row, so the way back to the frame top is exactly
     * {@code stamp.cursorRowFromTop()} rows, no more: over-moving would climb into committed scrollback and overwrite
     * it on every redraw.
     *
     * <p>
     * This under-moves on a terminal that reflows. The frame's dividers span the full width, so narrowing the window
     * re-wraps each already-drawn divider into two rows; walking up by the count recorded at the old width then lands
     * one row inside the old frame and leaves its top row on screen -- a stray divider above the prompt, one per
     * resize. Computing the reflowed height instead is possible (the drawn widths are known) but not safe: nothing
     * tells the application whether the terminal reflowed at all, and guessing wrong in the other direction walks up
     * into committed scrollback and destroys it. A visible artifact is the better failure, so the count stays as drawn.
     * {@code FrameIntegrityTest.widthChangeReanchorsWithoutTouchingScrollback} pins that choice.
     */
    public void eraseCurrent(PrintWriter out) {
        if (stamp.cursorRowFromTop() > 0)
            out.print("\033[" + stamp.cursorRowFromTop() + "A");
        out.print("\r");
    }

    public void renderAfterErase(PrintWriter out, int width, int terminalHeight, AnsiTheme theme,
                                    PromptSnapshot snapshot) {
        var frame = composeFrame(width, terminalHeight, theme, snapshot);
        emit(out, width, frame);
    }

    private record Frame(List<String> rows, int cursorRow, int cursorCol) {
    }

    private Frame composeFrame(int width, int terminalHeight, AnsiTheme theme, PromptSnapshot snapshot) {
        // Regions compose first: their total decides how many rows are left for the live stream tail.
        var bodies = new ArrayList<PromptRegion.Body>(regions.size());
        int regionsTotal = 0;
        for (var region : regions) {
            var body = region.compose(width, theme, snapshot);
            bodies.add(body);
            regionsTotal += body.rows().size();
        }

        var rows = new ArrayList<String>();
        int cursorRow = -1;
        int cursorCol = -1;

        // The in-progress line is previewed live above the separator, wrapped to physical rows by the SAME routine that
        // wraps committed lines (wrapPreservingAnsi) and at the same width, so styling, which never changes a glyph's
        // visible width, can't move a wrap point: committing the line changes only colors, never layout (no reflow).
        // The preview is capped to the rows left on screen after the separator + prompt regions, keeping the NEWEST
        // rows, so a long in-progress block streams in full yet can never push the prompt off the bottom. A non-null
        // tail (even "") reserves the row(s) for the streaming turn so the prompt doesn't jitter as lines commit; null
        // means idle (no preview). The tail arrives PRE-STYLED with the base slot only (content normal, thinking
        // dimmed); inline markdown styling and syntax highlighting are deferred to commit.
        String tail = snapshot.streamTail();
        if (tail != null) {
            int tailBudget = Math.max(1, terminalHeight - regionsTotal - 2); // less separator + one spare row
            var tailRows = AnsiWidth.wrapPreservingAnsi(tail, width);
            rows.addAll(tailRows.subList(Math.max(0, tailRows.size() - tailBudget), tailRows.size()));
        }

        // Top rule of the prompt frame (the Editor draws the matching bottom rule): a clean frame without a box; the
        // in-box caret carries state, so the rule stays plain. Tinted by state (red on error). Optional (top_divider).
        if (topDivider)
            rows.add(theme.styled(PromptRegion.ruleSlot(snapshot), PromptRegion.rule(width)));

        for (var body : bodies) {
            if (body.cursorRow() >= 0) {
                cursorRow = rows.size() + body.cursorRow();
                cursorCol = body.cursorCol();
            }
            rows.addAll(body.rows());
        }
        if (rows.isEmpty())
            rows.add("");

        // Clamp the frame to the screen, keeping the BOTTOM rows (the prompt): relative cursor moves can't reach rows
        // that scrolled off the top, so a taller-than-screen frame would lose its anchor and drift on every redraw.
        if (rows.size() > terminalHeight) {
            int drop = rows.size() - terminalHeight;
            rows.subList(0, drop).clear();
            cursorRow -= drop; // may go negative (caret row dropped); the park below falls back to the last row
        }
        return new Frame(rows, cursorRow, cursorCol);
    }

    private void emit(PrintWriter out, int width, Frame frame) {
        var rows = frame.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0)
                out.print("\r\n");
            out.print('\r');
            // Single choke point for row hygiene: no row may carry line breaks or exceed the terminal width; an
            // overlong row would auto-wrap into a physical row the frame never composed (auto-wrap is also disabled
            // around the frame as a second line of defence, where a width miscount degrades to a clipped glyph).
            String row = rows.get(i);
            if (row.indexOf('\n') >= 0 || row.indexOf('\r') >= 0)
                row = row.replace('\n', ' ').replace('\r', ' ');
            int vw = AnsiWidth.visibleWidth(row);
            if (vw > width) {
                row = AnsiWidth.truncateVisual(row, width);
                vw = width;
            }
            out.print(row);
            // Write-then-trim: clear leftover only after the row's content is drawn. Skipped for exactly-full rows:
            // there is nothing to their right, and on xterm-family terminals the cursor then sits deferred-wrap ON the
            // last column, where EL would erase the glyph just written.
            if (vw < width)
                out.print("\033[K");
        }

        // The cursor sits at the bottom of the freshly drawn frame; clear any rows a taller previous frame left below
        // it. This trims only stale rows beneath the new content (never anything just drawn), replacing the up-front
        // whole-region blank that would otherwise strobe.
        out.print("\033[J");

        int last = rows.size() - 1;
        int park = frame.cursorRow() >= 0 && frame.cursorRow() <= last ? frame.cursorRow() : last;
        if (last - park > 0)
            out.print("\033[" + (last - park) + "A");
        out.print('\r');
        if (park == frame.cursorRow() && frame.cursorCol() > 0)
            out.print("\033[" + frame.cursorCol() + "C");
        out.flush();

        stamp = new Stamp(rows.size(), park);
    }

}
