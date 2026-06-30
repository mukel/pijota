package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public sealed interface PromptRegion permits PromptRegion.Editor, PromptRegion.StatusBar, PromptRegion.Completions,
                                PromptRegion.Custom {

    int height(int width, PromptSnapshot ctx);

    CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx);
    static final CursorPos NO_CURSOR = new CursorPos(-1, -1);

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

    record Editor() implements PromptRegion {
        private record VisLine(String prefix, String prefixSlot, String text, int logicalRow, int startCol,
                                        int endCol) {
        }

        @Override
        public int height(int width, PromptSnapshot ctx) {
            return Math.max(1, visualLines(ctx, width).size());
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
                    String suggestion = ctx.suggestion();
                    if (suggestion != null && !suggestion.isEmpty()) {
                        out.print(theme.styled("dim", suggestion));
                    }
                }
                if (line.logicalRow() == ctx.cursorRow() && ctx.cursorCol() >= line.startCol()
                                                && ctx.cursorCol() <= line.endCol()) {
                    cursorVisualRow = i;
                    cursorVisualCol = line.prefix().length() + ctx.cursorCol() - line.startCol();
                }
                out.print("\033[K"); // write-then-trim: clear leftover only after the line's content is drawn
                if (i < lines.size() - 1)
                    out.print("\r\n");
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

        private static ArrayList<VisLine> visualLines(PromptSnapshot ctx, int width) {
            var out = new ArrayList<VisLine>();
            int bodyWidth = Math.max(1, width - 2);
            boolean continuation = ctx.promptState() == PromptSnapshot.PromptState.CONTINUATION;
            for (int row = 0; row < ctx.lines().size(); row++) {
                String text = ctx.lines().get(row);
                String firstPrefix = row == 0 ? promptPrefix(ctx) : continuation ? "┃ " : "│ ";
                String firstPrefixSlot = row == 0 ? promptPrefixSlot(ctx, text) : continuation ? "dim" : "statusText";
                if (text.isEmpty()) {
                    out.add(new VisLine(firstPrefix, firstPrefixSlot, "", row, 0, 0));
                    continue;
                }
                int start = 0;
                boolean first = true;
                while (start < text.length()) {
                    int end = Math.min(text.length(), start + bodyWidth);
                    String prefix = first ? firstPrefix : "  ";
                    String slot = first ? firstPrefixSlot : "statusText";
                    out.add(new VisLine(prefix, slot, text.substring(start, end), row, start, end));
                    start = end;
                    first = false;
                }
            }
            if (out.isEmpty())
                out.add(new VisLine(promptPrefix(ctx), promptPrefixSlot(ctx, ""), "", 0, 0, 0));
            return out;
        }

        private static String promptPrefix(PromptSnapshot ctx) {
            return switch (ctx.promptState()) {
                case BUSY -> "◇ ";
                case ERROR -> "✖ ";
                case CONTINUATION -> "┃ ";
                case QUESTION -> "❓ ";
                case IDLE -> ctx.lines().size() == 1 && ctx.lines().getFirst().isEmpty() ? "› " : "▸ ";
            };
        }

        private static String promptPrefixSlot(PromptSnapshot ctx, String firstLine) {
            if (ctx.promptState() == PromptSnapshot.PromptState.BUSY)
                return "statusText";
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
                h += 1; // activity (tokens/spinner/phase): one truncated row — the per-frame spinner can't change
                        // height
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

            // Activity line (tokens · spinner · phase): its own short row, truncated to a single line so the per-frame
            // spinner can never wrap and grow the region — which is what made the prompt jitter up/down.
            if (hasStatus) {
                out.print('\r');
                out.print(theme.styled("statusText", AnsiWidth.truncateVisual(ctx.status(), width)));
                out.print("\033[K");
                first = false;
            }

            // Workspace line (cwd · branch · model · thinking): wrapped, never truncated. Its content is stable within
            // a
            // turn, so wrapping it doesn't cause per-frame jitter, and no information is lost.
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
