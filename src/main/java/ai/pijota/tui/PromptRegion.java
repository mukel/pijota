package ai.pijota.tui;

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
                out.print(line.text());
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
            var out = new java.util.ArrayList<VisLine>();
            int bodyWidth = Math.max(1, width - 2);
            for (int row = 0; row < ctx.lines().size(); row++) {
                String text = ctx.lines().get(row);
                String firstPrefix = row == 0 ? promptPrefix(ctx) : "| ";
                String firstPrefixSlot = row == 0 ? promptPrefixSlot(ctx, text) : "statusText";
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
                case BUSY -> "~ ";
                case ERROR -> "! ";
                case CONTINUATION -> "| ";
                case IDLE -> "> ";
            };
        }

        private static String promptPrefixSlot(PromptSnapshot ctx, String firstLine) {
            if (ctx.promptState() == PromptSnapshot.PromptState.BUSY) return "statusText";
            if (ctx.promptState() == PromptSnapshot.PromptState.ERROR) return "toolError";
            if (ctx.promptState() == PromptSnapshot.PromptState.CONTINUATION) return "statusText";
            return firstLine.startsWith("!") ? "bashMode" : "prompt";
        }
    }

    record StatusBar() implements PromptRegion {
        @Override
        public int height(int width, PromptSnapshot ctx) {
            int h = 0;
            if (ctx.cwd() != null && !ctx.cwd().isBlank()) h += rows(ctx.cwd(), width);
            for (String line : ctx.extensionLines()) h += rows(line, width);
            if (ctx.status() != null && !ctx.status().isBlank()) {
                for (String part : ctx.status().split("\\n", -1))
                    h += rows(part, width);
            }
            return h;
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            boolean hasCwd = ctx.cwd() != null && !ctx.cwd().isBlank();
            boolean hasStatus = ctx.status() != null && !ctx.status().isBlank();
            boolean hasSubAgents = !ctx.extensionLines().isEmpty();
            if (!hasCwd && !hasStatus && !hasSubAgents) return NO_CURSOR;
            boolean first = true;
            if (hasCwd) {
                out.print('\r');
                out.print("\033[K");
                out.print(theme.styled("cwdLine", ctx.cwd()));
                first = false;
            }
            if (hasSubAgents) {
                for (String line : ctx.extensionLines()) {
                    if (!first) out.print("\r\n");
                    else out.print('\r');
                    out.print("\033[K");
                    out.print(styledLine(theme, "statusText", line));
                    first = false;
                }
            }
            if (hasStatus) {
                if (!first) out.print("\r\n");
                String[] parts = ctx.status().split("\\n", -1);
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) out.print("\r\n");
                    else out.print('\r');
                    out.print("\033[K");
                    String text = styledLine(theme, "statusBar", parts[i]);
                    if (i == parts.length - 1 && ctx.warning() != null && !ctx.warning().isBlank())
                        text += "  " + theme.styled("statusWarn", ctx.warning());
                    out.print(text);
                }
            }
            return NO_CURSOR;
        }

        private static String styledLine(AnsiTheme theme, String slot, String text) {
            if (text == null || text.isEmpty()) return "";
            String style = theme.style(slot);
            if (style.isEmpty()) return text;
            return style + text.replace(theme.reset(), theme.reset() + style) + theme.reset();
        }

        private static int rows(String text, int width) {
            width = Math.max(1, width);
            int w = AnsiWidth.visibleWidth(text);
            if (w <= 0) return 0;
            return (w + width - 1) / width;
        }
    }

    record Completions() implements PromptRegion {
        private static final int MAX_VISIBLE = 5;

        @Override
        public int height(int width, PromptSnapshot ctx) {
            int total = ctx.completions().size();
            if (total == 0) return 0;
            int visible = Math.min(MAX_VISIBLE, total);
            return 1 + visible + (total > MAX_VISIBLE ? 1 : 0);
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
            out.print(theme.styled("hr", "─".repeat(Math.max(10, width - 2))));

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
                    out.print(theme.styled("statusText", padRight(item.description(), maxLen, item.name())));
                } else {
                    out.print("  ");
                    out.print(theme.styled("statusText", item.name() + padRight(item.description(), maxLen, item.name())));
                }
            }

            if (total > MAX_VISIBLE) {
                out.print("\r\n");
                out.print('\r');
                out.print("\033[K");
                out.print(theme.styled("statusText", "  (" + (sel + 1) + "/" + total + ")"));
            }

            return NO_CURSOR;
        }

        private static String padRight(String desc, int maxLen, String name) {
            int pad = maxLen - name.length() + 4;
            return " ".repeat(Math.max(0, pad)) + desc;
        }
    }

    non-sealed interface Custom extends PromptRegion {}
}
