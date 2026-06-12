package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;
import ai.pijota.modes.SyntaxHighlighter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class StreamingMarkdownFormatter {
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-{3,}:?");

    private final TerminalMarkdownRenderer renderer;
    private AnsiTheme theme;
    private final String defaultTextSlot;
    private final boolean defaultItalic;
    private final StringBuilder table = new StringBuilder();
    private String pendingTableHeader;
    private boolean inFence;
    private SyntaxHighlighter fenceHighlighter;
    private boolean previousNonBlank;

    public StreamingMarkdownFormatter(AnsiTheme theme) {
        this(theme, "text", false);
    }

    public StreamingMarkdownFormatter(AnsiTheme theme, String defaultTextSlot, boolean defaultItalic) {
        this.theme = theme;
        this.defaultTextSlot = defaultTextSlot;
        this.defaultItalic = defaultItalic;
        this.renderer = new TerminalMarkdownRenderer(theme);
    }

    public void setWidth(int width) {
        renderer.setWidth(width);
    }

    public void setTheme(AnsiTheme theme) {
        this.theme = theme;
        renderer.setTheme(theme);
    }

    public List<StyledLine> format(String raw) {
        String line = raw == null ? "" : raw;
        String stripped = line.stripLeading();
        String marker = fenceMarker(stripped);
        if (marker != null)
            return toggleFence(stripped, marker);
        if (inFence) {
            var rendered = List.of(StyledLine.of(renderCodeLine(line)));
            previousNonBlank = true;
            return rendered;
        }
        return renderChunks(acceptMarkdownLine(line));
    }

    public List<StyledLine> flush() {
        var out = renderChunks(flushBufferedMarkdown());
        if (inFence) {
            leaveFence();
            out.add(fenceLine("```"));
            previousNonBlank = true;
        }
        return out;
    }

    public void reset() {
        table.setLength(0);
        pendingTableHeader = null;
        leaveFence();
        previousNonBlank = false;
    }

    private List<StyledLine> toggleFence(String stripped, String marker) {
        var out = new ArrayList<>(renderChunks(flushBufferedMarkdown()));
        if (inFence) {
            leaveFence();
            out.add(fenceLine("```"));
        } else {
            String info = enterFence(stripped, marker);
            out.add(fenceLine("```" + info));
        }
        previousNonBlank = true;
        return out;
    }

    private String enterFence(String stripped, String marker) {
        String info = stripped.substring(marker.length()).strip();
        inFence = true;
        String language = fenceLanguage(info);
        fenceHighlighter = language.isEmpty() ? null : new SyntaxHighlighter(language, theme);
        return info;
    }

    private void leaveFence() {
        inFence = false;
        fenceHighlighter = null;
    }

    private String renderCodeLine(String line) {
        if (fenceHighlighter != null)
            return fenceHighlighter.highlightLine(line);
        return styled("codeBody", line);
    }

    private List<String> acceptMarkdownLine(String line) {
        var out = new ArrayList<String>();
        if (line == null)
            line = "";

        if (line.isBlank()) {
            flushTableInto(out);
            flushPendingHeaderInto(out);
            out.add("");
            return out;
        }

        String stripped = line.strip();
        if (pendingTableHeader != null) {
            if (isTableSeparator(stripped)) {
                appendTableLine(pendingTableHeader);
                pendingTableHeader = null;
                appendTableLine(line);
                return out;
            }
            flushPendingHeaderInto(out);
        }

        if (inTable()) {
            if (line.indexOf('|') >= 0) {
                appendTableLine(line);
            } else {
                flushTableInto(out);
                out.add(line);
            }
            return out;
        }

        if (line.indexOf('|') >= 0)
            pendingTableHeader = line;
        else
            out.add(line);
        return out;
    }

    private List<String> flushBufferedMarkdown() {
        var out = new ArrayList<String>();
        flushTableInto(out);
        flushPendingHeaderInto(out);
        return out;
    }

    private boolean inTable() {
        return !table.isEmpty();
    }

    private void appendTableLine(String line) {
        if (!table.isEmpty())
            table.append('\n');
        table.append(line);
    }

    private void flushTableInto(List<String> out) {
        if (!inTable())
            return;
        out.add(table.toString());
        table.setLength(0);
    }

    private void flushPendingHeaderInto(List<String> out) {
        if (pendingTableHeader == null)
            return;
        out.add(pendingTableHeader);
        pendingTableHeader = null;
    }

    private List<StyledLine> renderChunks(List<String> chunks) {
        var out = new ArrayList<StyledLine>();
        for (var chunk : chunks) {
            if (chunk.isEmpty()) {
                out.add(StyledLine.of(""));
                previousNonBlank = false;
                continue;
            }
            if (previousNonBlank && startsWithHeading(chunk)) {
                out.add(StyledLine.of(""));
            }
            var rendered = renderer.render(chunk, defaultTextSlot, defaultItalic);
            out.addAll(rendered);
            previousNonBlank = rendered.stream().anyMatch(line -> !line.text().isEmpty());
        }
        return out;
    }

    private static String fenceMarker(String stripped) {
        if (stripped.startsWith("```"))
            return "```";
        if (stripped.startsWith("~~~"))
            return "~~~";
        return null;
    }

    private static String fenceLanguage(String info) {
        if (info == null || info.isBlank())
            return "";
        String lang = info.strip().split("\\s+", 2)[0].toLowerCase();
        if (lang.startsWith("language-"))
            lang = lang.substring("language-".length());
        return switch (lang) {
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            case "rb" -> "ruby";
            case "yml" -> "yaml";
            case "c++" -> "cpp";
            case "c#" -> "csharp";
            default -> lang;
        };
    }

    private StyledLine fenceLine(String text) {
        return StyledLine.of(styled("codeFence", text));
    }

    private String styled(String slot, String text) {
        return text == null || text.isEmpty() ? "" : theme.styled(slot, text);
    }

    private static boolean isTableSeparator(String stripped) {
        if (stripped.indexOf('|') < 0)
            return false;
        String s = stripped;
        if (s.startsWith("|"))
            s = s.substring(1);
        if (s.endsWith("|"))
            s = s.substring(0, s.length() - 1);
        for (String cell : s.split("\\|", -1)) {
            if (!TABLE_SEPARATOR_CELL.matcher(cell.strip()).matches())
                return false;
        }
        return true;
    }

    private static boolean startsWithHeading(String markdown) {
        if (markdown == null || markdown.isEmpty())
            return false;
        int end = markdown.indexOf('\n');
        String firstLine = end >= 0 ? markdown.substring(0, end) : markdown;
        int i = 0;
        while (i < firstLine.length() && firstLine.charAt(i) == ' ')
            i++;
        if (i >= firstLine.length() || firstLine.charAt(i) != '#')
            return false;
        int n = 0;
        while (i + n < firstLine.length() && firstLine.charAt(i + n) == '#')
            n++;
        return n < firstLine.length() && firstLine.charAt(i + n) == ' ';
    }
}
