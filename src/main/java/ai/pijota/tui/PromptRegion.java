package ai.pijota.tui;
import java.util.ArrayList;

import ai.pijota.theme.AnsiTheme;
import java.io.PrintWriter;

public sealed interface PromptRegion
    permits PromptRegion.Editor, PromptRegion.StatusBar,
            PromptRegion.Completions, PromptRegion.Custom {

    int height(int width, PromptSnapshot ctx);

    CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx);
    static final CursorPos NO_CURSOR = new CursorPos(-1, -1);

    record CursorPos(int visualRow, int visualCol) {}

    record Editor() implements PromptRegion {
        private record VisLine(String prefix, String prefixSlot, String text, int logicalRow, int startCol, int endCol) {}

        @Override
        public int height(int width, PromptSnapshot ctx) {
            return Math.max(1, visualLines(ctx, width).size());
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            var lines = visualLines(ctx, width);
            int cursorVisualRow = -1;
            int cursorVisualCol = -1;
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                out.print('\r');
                out.print("\033[K");
                out.print(theme.styled(line.prefixSlot(), line.prefix()));
                out.print(theme.styled("text", line.text()));
                if (line.logicalRow() == ctx.cursorRow()
                        && ctx.cursorCol() >= line.startCol()
                        && ctx.cursorCol() <= line.endCol()) {
                    cursorVisualRow = i;
                    cursorVisualCol = line.prefix().length() + ctx.cursorCol() - line.startCol();
                }
                if (i < lines.size() - 1) out.print("\r\n");
            }
            return cursorVisualRow >= 0 ? new CursorPos(cursorVisualRow, cursorVisualCol) : NO_CURSOR;
        }

        private static java.util.ArrayList<VisLine> visualLines(PromptSnapshot ctx, int width) {
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
            if (out.isEmpty()) out.add(new VisLine(promptPrefix(ctx), promptPrefixSlot(ctx, ""), "", 0, 0, 0));
            return out;
        }

        private static String promptPrefix(PromptSnapshot ctx) {
            return switch (ctx.promptState()) {
                case BUSY -> "◇ ";
                case ERROR -> "✖ ";
                case CONTINUATION -> "┃ ";
                case IDLE -> ctx.lines().size() == 1 && ctx.lines().getFirst().isEmpty() ? "› " : "▸ ";
            };
        }

        private static String promptPrefixSlot(PromptSnapshot ctx, String firstLine) {
            if (ctx.promptState() == PromptSnapshot.PromptState.BUSY) return "statusText";
            if (ctx.promptState() == PromptSnapshot.PromptState.ERROR) return "toolError";
            if (ctx.promptState() == PromptSnapshot.PromptState.CONTINUATION) return "dim";
            return firstLine.startsWith("!") ? "bashMode" : "prompt";
        }
    }

    record StatusBar() implements PromptRegion {
        private static final int MAX_EXTENSION_LINES = 3;

        @Override
        public int height(int width, PromptSnapshot ctx) {
            String primary = primaryLine(ctx);
            int h = 0;
            if (primary != null && !primary.isBlank()) h += primaryRows(primary, width);
            if (ctx.warning() != null && !ctx.warning().isBlank()) h += warningRows(ctx.warning(), width);
            if (!ctx.extensionLines().isEmpty()) h += Math.min(MAX_EXTENSION_LINES, ctx.extensionLines().size());
            if (ctx.extensionLines().size() > MAX_EXTENSION_LINES) h += 1;
            return h;
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            String primary = primaryLine(ctx);
            boolean hasWarning = ctx.warning() != null && !ctx.warning().isBlank();
            boolean hasExtensions = !ctx.extensionLines().isEmpty();
            if (primary == null && !hasWarning && !hasExtensions) return NO_CURSOR;
            boolean first = true;

            if (primary != null && !primary.isBlank()) {
                for (String segment : AnsiWidth.wrapPreservingAnsi(primary, width)) {
                    if (!first) out.print("\r\n");
                    else out.print('\r');
                    out.print("\033[K");
                    out.print(theme.styled("cwdLine", segment));
                    first = false;
                }
            }

            if (hasWarning) {
                for (String part : ctx.warning().split("\\n", -1)) {
                    if (!first) out.print("\r\n");
                    else out.print('\r');
                    out.print("\033[K");
                    out.print(theme.styled("statusWarn", AnsiWidth.truncateVisual("  " + part, width)));
                    first = false;
                }
            }

            if (hasExtensions) {
                int limit = Math.min(MAX_EXTENSION_LINES, ctx.extensionLines().size());
                for (int i = 0; i < limit; i++) {
                    if (!first) out.print("\r\n");
                    else out.print('\r');
                    out.print("\033[K");
                    out.print(theme.styled("statusText", AnsiWidth.truncateVisual("  " + ctx.extensionLines().get(i), width)));
                    first = false;
                }
                int extra = ctx.extensionLines().size() - MAX_EXTENSION_LINES;
                if (extra > 0) {
                    if (!first) out.print("\r\n");
                    else out.print('\r');
                    out.print("\033[K");
                    out.print(theme.styled("dim", "  … " + extra + " more"));
                    first = false;
                }
            }
            return NO_CURSOR;
        }

        private static String primaryLine(PromptSnapshot ctx) {
            boolean hasCwd = ctx.cwd() != null && !ctx.cwd().isBlank();
            boolean hasStatus = ctx.status() != null && !ctx.status().isBlank();
            if (!hasCwd && !hasStatus) return null;
            if (!hasCwd) return ctx.status();
            if (!hasStatus) return ctx.cwd();
            return ctx.status() + "  " + ctx.cwd();
        }

        private static int primaryRows(String line, int width) {
            width = Math.max(1, width);
            int w = AnsiWidth.visibleWidth(line);
            if (w <= 0) return 0;
            return (w + width - 1) / width;
        }

        private static int warningRows(String warning, int width) {
            int h = 0;
            for (String part : warning.split("\\n", -1)) {
                width = Math.max(1, width);
                int w = AnsiWidth.visibleWidth("  " + part);
                if (w <= 0) continue;
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
            if (total == 0) return 0;
            int visible = Math.min(MAX_VISIBLE, total);
            return 1 + visible + 1;
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            var list = ctx.completions();
            int total = list.size();
            if (total == 0) return NO_CURSOR;
            int sel = ctx.selectedCompletion();
            int maxLen = list.stream().mapToInt(c -> c.name().length()).max().orElse(0);

            out.print('\r');
            out.print("\033[K");
            out.print(theme.styled("hr", "suggestions"));

            int start = 0;
            if (total > MAX_VISIBLE) {
                int half = MAX_VISIBLE / 2;
                start = Math.max(0, Math.min(sel - half, total - MAX_VISIBLE));
            }
            int end = Math.min(start + MAX_VISIBLE, total);

            for (int i = start; i < end; i++) {
                var item = list.get(i);
                out.print("\r\n");
                out.print('\r');
                out.print("\033[K");
                if (i == sel) {
                    out.print(theme.styled("statusText", "→ "));
                    out.print(theme.styled("heading", item.name()));
                    out.print(theme.styled("statusText", padRight(item.description(), maxLen, item.name(), width)));
                } else {
                    out.print("  ");
                    out.print(theme.styled("statusText", item.name() + padRight(item.description(), maxLen, item.name(), width)));
                }
            }

            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            out.print(theme.styled("statusText", "  " + (sel + 1) + "/" + total));
            out.print(theme.styled("dim", "  · Enter · ↑/↓"));

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
