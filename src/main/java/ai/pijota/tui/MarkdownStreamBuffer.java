package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class MarkdownStreamBuffer {
    private enum Kind { NONE, PARAGRAPH, LIST, TABLE_CANDIDATE, TABLE, BLOCKQUOTE, INDENTED_CODE }

    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-{3,}:?");

    private final StringBuilder block = new StringBuilder();
    private Kind kind = Kind.NONE;
    private int width = 80;
    private int lines;
    private String lastTableHeader;

    void setWidth(int width) {
        if (width > 20) this.width = width;
    }

    List<MarkdownChunk> accept(String line) {
        var out = new ArrayList<MarkdownChunk>();
        if (line == null) line = "";

        if (line.isBlank()) {
            flushInto(out);
            lastTableHeader = null;
            out.add(MarkdownChunk.blank());
            return out;
        }

        Kind next = classify(line);
        if (kind == Kind.NONE) {
            if (lastTableHeader != null && next == Kind.TABLE_CANDIDATE) {
                kind = Kind.TABLE;
                block.append(lastTableHeader);
                lines = 2;
                append(line);
                lastTableHeader = null;
            } else {
                start(next, line);
                lastTableHeader = null;
            }
            if (shouldFlushCurrent()) flushInto(out);
            return out;
        }

        if (continues(line, next)) {
            if (kind == Kind.TABLE_CANDIDATE && next == Kind.TABLE) kind = Kind.TABLE;
            append(line);
            if (shouldFlushCurrent()) flushInto(out);
            return out;
        }

        flushInto(out);
        start(next, line);
        lastTableHeader = null;
        if (shouldFlushCurrent()) flushInto(out);
        return out;
    }

    List<MarkdownChunk> flush() {
        var out = new ArrayList<MarkdownChunk>();
        flushInto(out);
        return out;
    }

    private void start(Kind next, String line) {
        kind = next;
        append(line);
    }

    private void append(String line) {
        if (!block.isEmpty()) block.append('\n');
        block.append(line);
        lines++;
    }

    private void flushInto(List<MarkdownChunk> out) {
        if (block.isEmpty()) { clear(); return; }
        if (kind == Kind.TABLE) saveTableHeader();
        out.add(MarkdownChunk.markdown(block.toString()));
        clear();
    }

    private void saveTableHeader() {
        String text = block.toString();
        int first = text.indexOf('\n');
        if (first < 0) return;
        int second = text.indexOf('\n', first + 1);
        if (second < 0) return;
        lastTableHeader = text.substring(0, second);
    }

    void clear() {
        block.setLength(0);
        kind = Kind.NONE;
        lines = 0;
        lastTableHeader = null;
    }

    private boolean continues(String line, Kind next) {
        return switch (kind) {
            case PARAGRAPH -> next == Kind.PARAGRAPH;
            case LIST -> next == Kind.LIST || isIndented(line);
            case TABLE_CANDIDATE -> next == Kind.TABLE;
            case TABLE -> next == Kind.TABLE_CANDIDATE || next == Kind.TABLE;
            case BLOCKQUOTE -> next == Kind.BLOCKQUOTE;
            case INDENTED_CODE -> next == Kind.INDENTED_CODE;
            case NONE -> false;
        };
    }

    private Kind classify(String line) {
        String stripped = line.stripLeading();
        if (isHeading(stripped) || isRule(stripped)) return Kind.PARAGRAPH;
        if (isListItem(stripped)) return Kind.LIST;
        if (stripped.startsWith(">")) return Kind.BLOCKQUOTE;
        if (isIndented(line)) return Kind.INDENTED_CODE;
        if (isTableSeparator(stripped)) return Kind.TABLE;
        if (line.indexOf('|') >= 0) return Kind.TABLE_CANDIDATE;
        return Kind.PARAGRAPH;
    }

    private boolean shouldFlushCurrent() {
        return switch (kind) {
            case NONE, TABLE_CANDIDATE -> false;
            case TABLE -> lines >= 15 || block.length() >= Math.max(1000, width * 15);
            case PARAGRAPH, LIST, BLOCKQUOTE, INDENTED_CODE -> true;
        };
    }

    private static boolean isIndented(String line) {
        return line.startsWith("    ") || line.startsWith("\t");
    }

    private static boolean isHeading(String stripped) {
        int n = 0;
        while (n < stripped.length() && stripped.charAt(n) == '#') n++;
        return n > 0 && n <= 6 && n < stripped.length() && Character.isWhitespace(stripped.charAt(n));
    }

    private static boolean isListItem(String stripped) {
        if (stripped.startsWith("- ") || stripped.startsWith("* ") || stripped.startsWith("+ ")) return true;
        int dot = 0;
        while (dot < stripped.length() && Character.isDigit(stripped.charAt(dot))) dot++;
        return dot > 0 && dot < stripped.length() - 1 && ".)".indexOf(stripped.charAt(dot)) >= 0
            && Character.isWhitespace(stripped.charAt(dot + 1));
    }

    private static boolean isTableSeparator(String stripped) {
        if (stripped.indexOf('|') < 0) return false;
        String s = stripped;
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        for (String cell : s.split("\\|", -1)) {
            String c = cell.strip();
            if (!TABLE_SEPARATOR_CELL.matcher(c).matches()) return false;
        }
        return true;
    }

    private static boolean isRule(String line) {
        if (line.length() < 3) return false;
        char first = line.charAt(0);
        if (first != '-' && first != '*' && first != '_') return false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch != first && ch != ' ') return false;
        }
        return true;
    }
}
