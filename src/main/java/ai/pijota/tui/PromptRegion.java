package ai.pijota.tui;

import ai.pijota.config.PromptGlyphs;
import ai.pijota.theme.AnsiTheme;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public sealed interface PromptRegion permits PromptRegion.Editor, PromptRegion.StatusBar, PromptRegion.Completions,
                                PromptRegion.Custom {

    int height(int width, PromptSnapshot ctx);

    CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx);
    static final CursorPos NO_CURSOR = new CursorPos(-1, -1);

    /**
     * Colour of the prompt frame rules. Only genuinely input-relevant states restyle the frame — a running model must
     * not (the input stays a live follow-up box), so BUSY looks the same as IDLE. Red on error, soft-grey otherwise.
     */
    static String ruleSlot(PromptSnapshot ctx) {
        return ctx.promptState() == PromptSnapshot.PromptState.ERROR ? "toolError" : "hr";
    }

    /**
     * The ghost suffix clipped to a single line that fits {@code room} visible columns — so a long or multiline history
     * suggestion can't wrap onto the next terminal row and corrupt the prompt's cursor/layout bookkeeping.
     */
    static String ghostText(String suggestion, int room) {
        if (suggestion == null || room <= 0)
            return "";
        int newline = suggestion.indexOf('\n');
        String firstLine = newline >= 0 ? suggestion.substring(0, newline) : suggestion;
        return AnsiWidth.truncateVisual(firstLine, room);
    }

    /**
     * Columns available for text after a prefix of {@code prefixW} visible columns, keeping content off the frame's
     * right edge. The single source of truth for where the prompt wraps — so the ghost clip and the text wrap agree.
     */
    static int bodyWidth(int width, int prefixW) {
        return Math.max(1, width - Math.max(prefixW, 2));
    }

    /** Writes pre-rendered lines as a region body: each cleared to end-of-line, separated by CR/LF. */
    static void writeLines(PrintWriter out, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0)
                out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            out.print(lines.get(i));
        }
    }

    record CursorPos(int visualRow, int visualCol) {
    }

    record Editor(PromptGlyphs glyphs) implements PromptRegion {
        Editor() {
            this(PromptGlyphs.DEFAULTS);
        }

        private record VisLine(String prefix, String prefixSlot, String text, int logicalRow, int startCol,
                                        int endCol) {
        }

        @Override
        public int height(int width, PromptSnapshot ctx) {
            return (glyphs.bottomDivider() ? 1 : 0) + Math.max(1, visualLines(ctx, width).size());
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            var lines = visualLines(ctx, width);
            int cursorVisualRow = -1;
            int cursorVisualCol = -1;
            boolean cursorAtEndOfInput = cursorAtEndOfInput(ctx);
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                out.print('\r');
                out.print(theme.styled(line.prefixSlot(), line.prefix()));
                out.print(theme.styled("text", line.text()));
                if (cursorAtEndOfInput && line.logicalRow() == ctx.cursorRow() && ctx.cursorCol() >= line.startCol()
                                                && ctx.cursorCol() <= line.endCol()) {
                    // Clip the ghost to the columns left on this wrap line (bodyWidth − text already drawn) so it
                    // never spills onto the next terminal row.
                    int room = bodyWidth(width, AnsiWidth.visibleWidth(line.prefix()))
                                                    - AnsiWidth.visibleWidth(line.text());
                    String suggestion = ghostText(ctx.suggestion(), room);
                    if (!suggestion.isEmpty())
                        out.print(theme.styled("dim", suggestion));
                }
                if (line.logicalRow() == ctx.cursorRow() && ctx.cursorCol() >= line.startCol()
                                                && ctx.cursorCol() <= line.endCol()) {
                    cursorVisualRow = i;
                    cursorVisualCol = AnsiWidth.visibleWidth(line.prefix()) + ctx.cursorCol() - line.startCol();
                }
                out.print("\033[K"); // write-then-trim: clear leftover only after the line's content is drawn
                if (i < lines.size() - 1 || glyphs.bottomDivider())
                    out.print("\r\n"); // newline after each line (the bottom rule, if any, follows on its own row)
            }
            // Bottom rule closes the frame (top rule is the layout's separator above); tinted by state, no verticals.
            if (glyphs.bottomDivider()) {
                out.print('\r');
                out.print(theme.styled(ruleSlot(ctx), "─".repeat(Math.max(10, width - 2))));
                out.print("\033[K");
            }
            return cursorVisualRow >= 0 ? new CursorPos(cursorVisualRow, cursorVisualCol) : NO_CURSOR;
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
                // Width math keys off the prefix's VISIBLE width (not char count), so any glyph — wide emoji, "", a
                // multi-char caret — wraps and positions the cursor correctly.
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
                    int end = Math.min(text.length(), start + bodyWidth);
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
                case QUESTION -> glyphs.question();
                case CONTINUATION -> glyphs.continuation();
                // A running model doesn't change the input caret — the box is still a live follow-up prompt.
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
            if (ctx.promptState() == PromptSnapshot.PromptState.QUESTION)
                return "statusWarn";
            return firstLine.startsWith("!") ? "bashMode" : "prompt";
        }
    }

    record StatusBar() implements PromptRegion {

        @Override
        public int height(int width, PromptSnapshot ctx) {
            int h = 0;
            if (hasText(ctx.status()))
                h += wrappedRows(ctx.status(), width); // stats line: wrapped, spills to the next row on a narrow term
            if (hasText(ctx.cwd()))
                h += wrappedRows(ctx.cwd(), width); // workspace info: wrapped, but stable within a turn (no jitter)
            if (hasText(ctx.warning()))
                h += warningRows(ctx.warning(), width);
            h += ctx.extensionLines().size(); // already collapsed/expanded by the supplier (see AppendOnlyTui.collapse)
            return h;
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            boolean hasStatus = hasText(ctx.status());
            boolean hasCwd = hasText(ctx.cwd());
            boolean hasWarning = hasText(ctx.warning());
            boolean hasExtensions = !ctx.extensionLines().isEmpty();
            if (!hasStatus && !hasCwd && !hasWarning && !hasExtensions)
                return NO_CURSOR;
            boolean first = true;

            // Workspace line FIRST (cwd · branch · session): wrapped, never truncated — a long path spills to the next
            // row rather than losing its tail. Stable within a turn, so wrapping it causes no per-frame jitter.
            if (hasCwd) {
                for (String seg : AnsiWidth.wrapPreservingAnsi(ctx.cwd(), width)) {
                    if (!first)
                        out.print("\r\n");
                    else
                        out.print('\r');
                    out.print(theme.styled("cwdLine", seg));
                    out.print("\033[K");
                    first = false;
                }
            }

            // Stats line (token info · (provider) model · reasoning · activity): pre-styled per segment, wrapped so a
            // narrow terminal spills to the next row instead of truncating.
            if (hasStatus) {
                for (String seg : AnsiWidth.wrapPreservingAnsi(ctx.status(), width)) {
                    if (!first)
                        out.print("\r\n");
                    else
                        out.print('\r');
                    out.print(seg);
                    out.print("\033[K");
                    first = false;
                }
            }

            if (hasWarning) {
                for (String part : ctx.warning().split("\\n", -1)) {
                    if (!first)
                        out.print("\r\n");
                    else
                        out.print('\r');
                    out.print(theme.styled("statusWarn", AnsiWidth.truncateVisual("  " + part, width)));
                    out.print("\033[K");
                    first = false;
                }
            }

            if (hasExtensions) {
                // The supplier already capped/expanded the list (AppendOnlyTui.collapse); render whatever it gave,
                // dimming the "… N more / collapse" affordance line.
                for (String line : ctx.extensionLines()) {
                    if (!first)
                        out.print("\r\n");
                    else
                        out.print('\r');
                    String style = line.startsWith("… ") ? "dim" : "statusText";
                    out.print(theme.styled(style, AnsiWidth.truncateVisual("  " + line, width)));
                    out.print("\033[K");
                    first = false;
                }
            }
            return NO_CURSOR;
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }

        private static int wrappedRows(String line, int width) {
            int w = AnsiWidth.visibleWidth(line);
            if (w <= 0)
                return 0;
            width = Math.max(1, width);
            return (w + width - 1) / width;
        }

        private static int warningRows(String warning, int width) {
            int h = 0;
            for (String part : warning.split("\\n", -1)) {
                width = Math.max(1, width);
                int w = AnsiWidth.visibleWidth("  " + part);
                if (w <= 0)
                    continue;
                h += (w + width - 1) / width;
            }
            return h;
        }

    }

    record Completions() implements PromptRegion {
        private static final int MAX_VISIBLE = 5;

        @Override
        public int height(int width, PromptSnapshot ctx) {
            int total = ctx.completions().size();
            if (total == 0)
                return 0;
            int visible = Math.min(MAX_VISIBLE, total);
            return 1 + visible + 1;
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            var list = ctx.completions();
            int total = list.size();
            if (total == 0)
                return NO_CURSOR;
            int sel = ctx.selectedCompletion();
            int maxLen = list.stream().mapToInt(c -> c.name().length()).max().orElse(0);

            out.print('\r');
            out.print("\033[K");
            // Every row is clamped to width: height() promises 1+visible+1 rows, so a row that overflowed and
            // terminal-auto-wrapped would draw more rows than measured and desync the frame (stacking separators).
            out.print(AnsiWidth.truncateVisual(theme.styled("hr", "suggestions"), width));

            int start = 0;
            if (total > MAX_VISIBLE) {
                int half = MAX_VISIBLE / 2;
                start = Math.max(0, Math.min(sel - half, total - MAX_VISIBLE));
            }
            int end = Math.min(start + MAX_VISIBLE, total);

            for (int i = start; i < end; i++) {
                var item = list.get(i);
                String row = i == sel
                                                ? theme.styled("statusText", "→ ")
                                                                                + theme.styled("heading", item.name())
                                                                                + theme.styled("statusText", padRight(
                                                                                                                item.description(),
                                                                                                                maxLen,
                                                                                                                item.name(),
                                                                                                                width))
                                                : "  " + theme.styled("statusText", item.name() + padRight(
                                                                                item.description(), maxLen, item.name(),
                                                                                width));
                out.print("\r\n");
                out.print('\r');
                out.print("\033[K");
                out.print(AnsiWidth.truncateVisual(row, width));
            }

            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            out.print(AnsiWidth.truncateVisual(
                                            theme.styled("statusText", "  " + (sel + 1) + "/" + total)
                                                                            + theme.styled("dim", "  · Enter · ↑/↓"),
                                            width));

            return NO_CURSOR;
        }

        private static String padRight(String desc, int maxLen, String name, int width) {
            int pad = maxLen - name.length() + 4;
            int detailWidth = Math.max(8, width - maxLen - 8);
            return " ".repeat(Math.max(0, pad)) + AnsiWidth.truncateVisual(desc, detailWidth);
        }
    }

    non-sealed interface Custom extends PromptRegion {}
}
