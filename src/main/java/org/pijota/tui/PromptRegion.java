package org.pijota.tui;

import org.pijota.config.PromptGlyphs;
import org.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;

public sealed interface PromptRegion permits PromptRegion.Editor, PromptRegion.StatusBar, PromptRegion.Completions,
                                PromptRegion.Custom {

    /**
     * A region's content for one frame: pre-styled rows plus an optional caret position. Rows are plain styled text (no
     * newlines, no cursor movement); {@link PromptLayout} clamps each row to the terminal width and owns all emission,
     * so the frame's height is <em>defined</em> as the number of composed rows. There is no separate measurement to
     * keep in sync: a region physically cannot make the frame taller or shorter than what it returned.
     */
    record Body(List<String> rows, int cursorRow, int cursorCol) {
        static final Body EMPTY = new Body(List.of(), -1, -1);

        public Body {
            rows = List.copyOf(rows);
        }

        static Body of(List<String> rows) {
            return new Body(rows, -1, -1);
        }
    }

    /** Composes this region's rows for one frame. Called exactly once per frame, on the terminal writer thread. */
    Body compose(int width, AnsiTheme theme, PromptSnapshot ctx);

    /**
     * Colour of the prompt frame rules. Only genuinely input-relevant states restyle the frame; a running model must
     * not (the input stays a live follow-up box), so BUSY looks the same as IDLE. Red on error, soft-grey otherwise.
     */
    static String ruleSlot(PromptSnapshot ctx) {
        return ctx.promptState() == PromptSnapshot.PromptState.ERROR ? "toolError" : "hr";
    }

    /**
     * A horizontal rule, deliberately one column short of the terminal.
     *
     * <p>
     * A row that fills the last column is marked "wrapped" by the terminal, and a wrapped row is re-flowed when the
     * window narrows: the rule splits into a full row plus a stub, every row below it shifts, and the frame's own
     * re-anchor lands on the wrong line. What the user sees after dragging a window edge is two rules stacked above the
     * prompt and blank rows threaded through output that was already on screen -- damage to scrollback the frame can
     * never repair, because it is behind the anchor.
     *
     * <p>
     * Reproducible without pijota: print 100 {@code ─} in a 100-column tmux pane, resize to 99, and the line comes back
     * as 99 + 1. Print 99 in that same pane and it survives untouched. One column is the whole difference, and the gap
     * it leaves at the right edge is invisible next to what it prevents.
     */
    static String rule(int width) {
        return "─".repeat(Math.max(1, width - 1));
    }

    /**
     * The ghost suffix clipped to a single line that fits {@code room} visible columns, so a long or multiline history
     * suggestion can't wrap onto the next terminal row and corrupt the prompt's cursor/layout bookkeeping.
     */
    static String ghostText(String suggestion, int room) {
        if (suggestion == null || room <= 0)
            return "";
        int newline = suggestion.indexOf('\n');
        String firstLine = newline >= 0 ? suggestion.substring(0, newline) : suggestion;
        if (AnsiWidth.visibleWidth(firstLine) <= room)
            return firstLine;
        // A clipped suggestion with no cue reads as a finished one -- "explain what divide does in one" looks like the
        // whole thought rather than the first two thirds of it. The ellipsis says there is more, and Tab still accepts
        // the full text: the clip is display only.
        return room <= 1 ? "…" : AnsiWidth.truncateVisual(firstLine, room - 1) + "…";
    }

    /**
     * Columns available for text after a prefix of {@code prefixW} visible columns, keeping content off the frame's
     * right edge. The single source of truth for where the prompt wraps, so the ghost clip and the text wrap agree.
     */
    static int bodyWidth(int width, int prefixW) {
        return Math.max(1, width - Math.max(prefixW, 2));
    }

    record Editor(PromptGlyphs glyphs) implements PromptRegion {
        Editor() {
            this(PromptGlyphs.DEFAULTS);
        }

        private record VisLine(String prefix, String prefixSlot, String text, int logicalRow, int startCol,
                                        int endCol) {
        }

        @Override
        public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
            var lines = visualLines(ctx, width);
            var rows = new ArrayList<String>(lines.size() + 1);
            int cursorRow = -1;
            int cursorCol = -1;
            boolean cursorAtEndOfInput = cursorAtEndOfInput(ctx);
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                var row = new StringBuilder();
                row.append(theme.styled(line.prefixSlot(), line.prefix()));
                row.append(theme.styled("text", line.text()));
                boolean cursorHere = line.logicalRow() == ctx.cursorRow() && ctx.cursorCol() >= line.startCol()
                                                && ctx.cursorCol() <= line.endCol();
                if (cursorAtEndOfInput && cursorHere) {
                    // Clip the ghost to the columns left on this wrap line (bodyWidth − text already drawn) so it
                    // never spills onto the next terminal row.
                    int room = bodyWidth(width, AnsiWidth.visibleWidth(line.prefix()))
                                                    - AnsiWidth.visibleWidth(line.text());
                    String suggestion = ghostText(ctx.suggestion(), room);
                    if (!suggestion.isEmpty())
                        row.append(theme.styled("dim", suggestion));
                }
                if (cursorHere) {
                    cursorRow = i;
                    // Caret column in visible columns, not chars; wide glyphs before the caret shift it two cells.
                    cursorCol = AnsiWidth.visibleWidth(line.prefix()) + AnsiWidth.visibleWidth(
                                                    line.text().substring(0, ctx.cursorCol() - line.startCol()));
                }
                rows.add(row.toString());
            }
            // Bottom rule closes the frame (top rule is the layout's separator above); tinted by state, no verticals.
            if (glyphs.bottomDivider())
                rows.add(theme.styled(ruleSlot(ctx), rule(width)));
            return new Body(rows, cursorRow, cursorCol);
        }

        private static boolean cursorAtEndOfInput(PromptSnapshot ctx) {
            if (ctx.lines().isEmpty())
                return false;
            int lastRow = ctx.lines().size() - 1;
            String lastLine = ctx.lines().get(lastRow);
            return ctx.cursorRow() == lastRow && ctx.cursorCol() == lastLine.length();
        }

        private ArrayList<VisLine> visualLines(PromptSnapshot ctx, int width) {
            var out = new ArrayList<VisLine>();
            String firstPrefix = withSpace(caretGlyph(ctx)); // configurable caret ("" = none)
            String contPrefix = withSpace(barGlyph(ctx)); // configurable continuation bar ("" = none)
            String firstLineText = ctx.lines().isEmpty() ? "" : ctx.lines().get(0);
            String firstSlot = promptPrefixSlot(ctx, firstLineText);
            String contSlot = ctx.promptState() == PromptSnapshot.PromptState.CONTINUATION ? "dim" : "statusText";
            for (int row = 0; row < ctx.lines().size(); row++) {
                String text = ctx.lines().get(row);
                String prefix = row == 0 ? firstPrefix : contPrefix;
                String slot = row == 0 ? firstSlot : contSlot;
                // Width math keys off the prefix's VISIBLE width (not char count), so any glyph (wide emoji, "", a
                // multi-char caret) wraps and positions the cursor correctly.
                int prefixW = AnsiWidth.visibleWidth(prefix);
                int bodyWidth = bodyWidth(width, prefixW);
                String indent = " ".repeat(prefixW); // wrapped rows align under the first row's text
                if (text.isEmpty()) {
                    out.add(new VisLine(prefix, slot, "", row, 0, 0));
                    continue;
                }
                int start = 0;
                boolean first = true;
                while (start < text.length()) {
                    // Wrap by visible COLUMNS, not chars: a CJK/emoji glyph occupies two cells, and a char-counted
                    // slice would overflow the terminal row and auto-wrap: an extra physical row the frame's
                    // bookkeeping would never see.
                    int end = AnsiWidth.wrapIndex(text, start, bodyWidth);
                    out.add(new VisLine(first ? prefix : indent, first ? slot : "statusText",
                                                    text.substring(start, end), row, start, end));
                    start = end;
                    first = false;
                }
            }
            if (out.isEmpty())
                out.add(new VisLine(firstPrefix, firstSlot, "", 0, 0, 0));
            return out;
        }

        private String caretGlyph(PromptSnapshot ctx) {
            return switch (ctx.promptState()) {
                case ERROR -> glyphs.error();
                case AWAITING_ANSWER -> glyphs.question();
                case CONTINUATION -> glyphs.continuation();
                // A running model doesn't change the input caret; the box is still a live follow-up prompt.
                case IDLE, BUSY -> glyphs.caret();
            };
        }

        private String barGlyph(PromptSnapshot ctx) {
            return ctx.promptState() == PromptSnapshot.PromptState.CONTINUATION ? glyphs.continuation() : glyphs.bar();
        }

        /** A glyph plus its trailing space, or "" when the glyph is empty (no prefix, no dangling space). */
        private static String withSpace(String glyph) {
            return glyph.isEmpty() ? "" : glyph + " ";
        }

        private static String promptPrefixSlot(PromptSnapshot ctx, String firstLine) {
            if (ctx.promptState() == PromptSnapshot.PromptState.ERROR)
                return "toolError";
            if (ctx.promptState() == PromptSnapshot.PromptState.CONTINUATION)
                return "dim";
            if (ctx.promptState() == PromptSnapshot.PromptState.AWAITING_ANSWER)
                return "statusWarn";
            return firstLine.startsWith("!") ? "bashMode" : "prompt";
        }
    }

    record StatusBar() implements PromptRegion {

        @Override
        public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
            var rows = new ArrayList<String>();

            // Workspace line FIRST (cwd · branch · session): always ONE row. A deep path used to spill over several
            // rows, eating a tenth of a narrow terminal; eliding the middle keeps what identifies the workspace (the
            // root and the project/branch tail) at a fixed cost. Stable within a turn, so no per-frame jitter.
            if (hasText(ctx.cwd()))
                rows.add(theme.styled("cwdLine", AnsiWidth.truncateMiddle(ctx.cwd(), width)));

            // Stats line (token info · (provider) model · reasoning · activity): pre-styled per segment, wrapped so a
            // narrow terminal spills to the next row instead of truncating.
            if (hasText(ctx.status()))
                rows.addAll(AnsiWidth.wrapPreservingAnsi(ctx.status(), width));

            if (hasText(ctx.warning()))
                for (String part : ctx.warning().split("\\n", -1))
                    rows.add(theme.styled("statusWarn", AnsiWidth.truncateVisual("  " + part, width)));

            // The supplier already capped/expanded the list (AppendOnlyTui.collapse); render whatever it gave,
            // dimming the "… N more / collapse" affordance line.
            for (String line : ctx.extensionLines())
                rows.add(theme.styled(line.startsWith("… ") ? "dim" : "statusText",
                                                AnsiWidth.truncateVisual("  " + line, width)));

            return Body.of(rows);
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }

    record Completions() implements PromptRegion {
        private static final int MAX_VISIBLE = 5;

        @Override
        public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
            var list = ctx.completions();
            int total = list.size();
            if (total == 0)
                return Body.EMPTY;
            int sel = ctx.selectedCompletion();
            int maxLen = list.stream().mapToInt(c -> c.name().length()).max().orElse(0);

            var rows = new ArrayList<String>();
            rows.add(AnsiWidth.truncateVisual(theme.styled("hr", "suggestions"), width));

            int start = 0;
            if (total > MAX_VISIBLE) {
                int half = MAX_VISIBLE / 2;
                start = Math.max(0, Math.min(sel - half, total - MAX_VISIBLE));
            }
            int end = Math.min(start + MAX_VISIBLE, total);

            for (int i = start; i < end; i++) {
                var item = list.get(i);
                String row = i == sel
                                                ? theme.styled("prompt", "› ") + theme.styled("heading", item.name())
                                                                                + theme.styled("statusText", padRight(
                                                                                                                item.description(),
                                                                                                                maxLen,
                                                                                                                item.name(),
                                                                                                                width))
                                                : "  " + theme.styled("statusText", item.name() + padRight(
                                                                                item.description(), maxLen, item.name(),
                                                                                width));
                rows.add(AnsiWidth.truncateVisual(row, width));
            }

            rows.add(TuiCopy.footer(theme, width, (sel + 1) + "/" + total, "Enter select", "↑/↓ navigate",
                                            "Esc cancel"));
            return Body.of(rows);
        }

        private static String padRight(String desc, int maxLen, String name, int width) {
            int pad = maxLen - name.length() + 4;
            int detailWidth = Math.max(8, width - maxLen - 8);
            return " ".repeat(Math.max(0, pad)) + AnsiWidth.truncateVisual(desc, detailWidth);
        }
    }

    non-sealed interface Custom extends PromptRegion {}
}
