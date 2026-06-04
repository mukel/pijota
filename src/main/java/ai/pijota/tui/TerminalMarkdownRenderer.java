package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;

final class TerminalMarkdownRenderer {
    private AnsiTheme theme;
    private int width = 80;

    TerminalMarkdownRenderer(AnsiTheme theme) {
        this.theme = theme;
    }

    void setTheme(AnsiTheme theme) {
        this.theme = theme;
    }

    void setWidth(int width) {
        if (width <= 20) return;
        this.width = width;
    }

    // ---- public API -------------------------------------------

    List<StyledLine> render(String markdown, String plainSlot) {
        if (markdown == null || markdown.isEmpty()) return List.of();
        var table = MarkdownTableRenderer.renderIfTable(markdown, theme, width);
        if (table.isPresent()) return table.get();
        return renderSource(markdown, plainSlot);
    }

    private List<StyledLine> renderSource(String markdown, String plainSlot) {
        var out = new ArrayList<StyledLine>();
        for (String line : markdown.split("\\R", -1)) {
            out.add(StyledLine.of(highlightSourceLine(line, plainSlot)));
        }
        return out;
    }

    private String highlightSourceLine(String line, String plainSlot) {
        if (line.isEmpty()) return line;
        int prefixEnd = markdownPrefixEnd(line);
        String prefix = prefixEnd == 0 ? "" : highlightPrefix(line.substring(0, prefixEnd));
        String body = line.substring(prefixEnd);
        if (isHeadingPrefix(line, prefixEnd)) return prefix + headingBody(body);
        String highlightedBody = highlightInlineSource(body);
        if (plainSlot != null && prefixEnd == 0 && highlightedBody.equals(body)) return slotStyled(plainSlot, line);
        return prefix + highlightedBody;
    }

    private boolean isHeadingPrefix(String line, int prefixEnd) {
        if (prefixEnd <= 0) return false;
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        return i < prefixEnd && line.charAt(i) == '#';
    }

    private int markdownPrefixEnd(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        String rest = line.substring(i);
        if (rest.startsWith("#")) {
            int n = 0;
            while (n < rest.length() && rest.charAt(n) == '#') n++;
            if (n < rest.length() && Character.isWhitespace(rest.charAt(n))) return i + n;
        }
        if (rest.startsWith(">")) return i + 1;
        if (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ")) return i + 1;
        int markerEnd = orderedMarkerEnd(rest);
        return markerEnd > 0 ? i + markerEnd : 0;
    }

    private String highlightPrefix(String prefix) {
        int i = 0;
        while (i < prefix.length() && Character.isWhitespace(prefix.charAt(i))) i++;
        String slot = "dim";
        if (i < prefix.length() && Character.isDigit(prefix.charAt(i))) {
            slot = "statusText";
        }
        return prefix.substring(0, i) + slotStyled(slot, prefix.substring(i));
    }

    private String highlightInlineSource(String line) {
        var out = new StringBuilder();
        for (int i = 0; i < line.length();) {
            int next = appendCheckbox(line, i, out);
            if (next < 0) next = appendDelimited(line, i, "`", "inlineCode", out);
            if (next < 0) next = appendDelimited(line, i, "**", "bold", out);
            if (next < 0) next = appendDelimited(line, i, "~~", "strikethrough", out);
            if (next >= 0) { i = next; continue; }
            out.append(line.charAt(i++));
        }
        return out.toString();
    }

    private int appendCheckbox(String line, int start, StringBuilder out) {
        if (start + 3 > line.length()) return -1;
        if (line.charAt(start) != '[' || line.charAt(start + 2) != ']') return -1;
        char c = line.charAt(start + 1);
        String slot = switch (c) {
            case 'x', 'X' -> "diffAdd";
            case ' ' -> "dim";
            case '~' -> "statusWarn";
            default -> null;
        };
        if (slot == null) return -1;
        out.append(slotStyled(slot, line.substring(start, start + 3)));
        int end = start + 3;
        if (end < line.length() && line.charAt(end) == ' ') {
            out.append(' ');
            end++;
        }
        return end;
    }

    private int appendDelimited(String line, int start, String delimiter, String slot, StringBuilder out) {
        if (!line.startsWith(delimiter, start)) return -1;
        int end = line.indexOf(delimiter, start + delimiter.length());
        if (end < 0) return -1;
        out.append(slotStyled("dim", delimiter));
        out.append(slotStyled(slot, line.substring(start + delimiter.length(), end)));
        out.append(slotStyled("dim", delimiter));
        return end + delimiter.length();
    }

    private static int orderedMarkerEnd(String text) {
        int i = 0;
        while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        if (i == 0 || i >= text.length() - 1) return -1;
        char marker = text.charAt(i);
        return (marker == '.' || marker == ')') && Character.isWhitespace(text.charAt(i + 1)) ? i + 1 : -1;
    }

    // ---- helpers ----------------------------------------------

    private String headingBody(String body) {
        String styled = slotStyled("heading", body);
        if (styled.isEmpty()) return styled;
        return theme.enabled ? "\033[1m" + styled : styled;
    }

    private String slotStyled(String slot, String text) {
        if (text == null || text.isEmpty()) return "";
        return theme.styled(slot, text);
    }

}
