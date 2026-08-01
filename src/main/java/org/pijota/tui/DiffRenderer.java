package org.pijota.tui;

import org.pijota.modes.SyntaxHighlighter;
import org.pijota.theme.AnsiTheme;
import org.pijota.util.DiffAlgorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class DiffRenderer {
    private static final int CONTEXT_LINES = 3;
    private static final int MAX_RENDERED_ROWS = 300;
    private static final int MIN_RULE = 5; // the header always ends in at least a stub of rule
    private static final Pattern ANSI_RESET = Pattern.compile("\033\\[0m");

    private final AnsiTheme theme;

    public DiffRenderer(AnsiTheme theme) {
        this.theme = theme;
    }

    public List<String> render(String action, String path, String oldText, String newText, int width) {
        String[] oldLines = oldText != null && !oldText.isEmpty() ? oldText.split("\\R", -1) : new String[0];
        String[] newLines = newText != null && !newText.isEmpty() ? newText.split("\\R", -1) : new String[0];
        var rows = DiffAlgorithm.diff(oldText, newText);
        int added = 0;
        int deleted = 0;
        for (var row : rows) {
            if (row.kind() == DiffAlgorithm.Kind.ADD)
                added++;
            if (row.kind() == DiffAlgorithm.Kind.DELETE)
                deleted++;
        }
        if (added == 0 && deleted == 0)
            return List.of();

        var highlighter = new SyntaxHighlighter(SyntaxHighlighter.languageForFilename(path), theme);
        var hunks = DiffAlgorithm.hunks(rows, CONTEXT_LINES);
        int maxOld = Math.max(1, oldLines.length);
        int maxNew = Math.max(1, newLines.length);
        int oldWidth = digits(maxOld);
        int newWidth = digits(maxNew);
        int terminalWidth = Math.max(40, width);

        var out = new ArrayList<String>();
        out.add(header(action, path, added, deleted, terminalWidth));
        out.add("");
        int rendered = 0;
        boolean truncated = false;

        for (int h = 0; h < hunks.size(); h++) {
            if (h > 0)
                out.add("");
            var hunk = hunks.get(h);
            for (var row : hunk.rows()) {
                if (rendered >= MAX_RENDERED_ROWS) {
                    truncated = true;
                    break;
                }
                out.addAll(format(row, oldWidth, newWidth, highlighter.highlightLine(row.text()), terminalWidth));
                rendered++;
            }
            if (truncated)
                break;
        }

        if (truncated) {
            int changed = added + deleted;
            out.add(theme.styled("diffMeta", "… diff truncated: showing " + MAX_RENDERED_ROWS + " of " + changed
                                            + " changed rows"));
        }
        return out;
    }

    private String header(String action, String path, int added, int deleted, int width) {
        String name = path == null || path.isBlank() ? "diff" : shortenPath(path);
        // Budget the path to what is left after the label, the counts and a stub of rule. An absolute path longer than
        // the terminal used to wrap mid-path, so the header broke across two rows and the second row started with the
        // middle of a directory name. Truncating the middle keeps both ends, and the filename is the end that matters.
        int fixed = action.length() + 1 + 2 + ("+" + added).length() + 1 + ("-" + deleted).length() + 1 + MIN_RULE;
        name = AnsiWidth.truncateMiddle(name, Math.max(12, width - fixed));
        // Label first, then the rule filling the line. The header used to open with "- ", which sat right next to the
        // "-0" deleted count and above a gutter of "-" markers, so the one dash that meant "rule" looked like the
        // three that mean "removed". The rule is drawn with ─, the same glyph as every other rule in the UI.
        String text = theme.styled("diffHeader", action + " " + name) + "  " + theme.styled("diffAdd", "+" + added)
                                        + " " + theme.styled("diffDelete", "-" + deleted) + " ";
        int fill = Math.max(MIN_RULE, width - AnsiWidth.visibleWidth(text));
        return text + theme.styled("diffMeta", "─".repeat(fill));
    }

    /**
     * One diff row, as the physical lines it occupies. A row longer than the terminal is wrapped here rather than left
     * to the terminal, which would restart the overflow at column zero: the code would then sit outside the line-number
     * gutter and under a different row's numbers. Continuations are indented to the gutter width, so wrapped code stays
     * under the code column and the numbers still read straight down.
     */
    private List<String> format(DiffAlgorithm.Row row, int oldWidth, int newWidth, String body, int width) {
        String oldNo = row.oldLine() > 0 ? pad(row.oldLine(), oldWidth) : " ".repeat(oldWidth);
        String newNo = row.newLine() > 0 ? pad(row.newLine(), newWidth) : " ".repeat(newWidth);
        String mark = switch (row.kind()) {
            case ADD -> "+";
            case DELETE -> "-";
            case CONTEXT -> " ";
        };
        String slot = switch (row.kind()) {
            case ADD -> "diffAdd";
            case DELETE -> "diffDelete";
            case CONTEXT -> "diffContext";
        };
        String prefix = theme.styled("diffMeta", "  " + oldNo + " " + newNo + " ") + theme.styled(slot, mark) + " ";
        int gutter = AnsiWidth.visibleWidth(prefix);
        String continuation = " ".repeat(gutter);
        var out = new ArrayList<String>();
        var wrapped = AnsiWidth.wrapPreservingAnsi(body, Math.max(8, width - gutter));
        for (int i = 0; i < wrapped.size(); i++) {
            String line = (i == 0 ? prefix : continuation) + wrapped.get(i)
                                            + (row.kind() == DiffAlgorithm.Kind.CONTEXT ? "" : " ");
            out.add(switch (row.kind()) {
                case ADD -> withBackground("diffAddLine", line);
                case DELETE -> withBackground("diffDeleteLine", line);
                case CONTEXT -> line;
            });
        }
        return out;
    }

    private String withBackground(String slot, String line) {
        String bg = theme.style(slot);
        if (bg.isBlank())
            return line;
        var sb = new StringBuilder(bg);
        int last = 0;
        var matcher = ANSI_RESET.matcher(line);
        while (matcher.find()) {
            sb.append(line, last, matcher.end()).append(bg);
            last = matcher.end();
        }
        sb.append(line.substring(last));
        sb.append(theme.reset());
        return sb.toString();
    }

    private static String shortenPath(String path) {
        String home = System.getProperty("user.home");
        if (home != null && path.startsWith(home))
            path = "~" + path.substring(home.length());
        return path;
    }

    private static int digits(int value) {
        return Integer.toString(Math.max(1, value)).length();
    }

    private static String pad(int value, int width) {
        return String.format(Locale.ROOT, "%" + width + "d", value);
    }
}
