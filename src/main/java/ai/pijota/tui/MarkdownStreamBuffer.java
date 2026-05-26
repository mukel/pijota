package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class MarkdownStreamBuffer {
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-{3,}:?");

    private final StringBuilder table = new StringBuilder();
    private String pendingTableHeader;

    List<MarkdownChunk> accept(String line) {
        var out = new ArrayList<MarkdownChunk>();
        if (line == null) line = "";

        if (line.isBlank()) {
            flushTableInto(out);
            flushPendingHeaderInto(out);
            out.add(MarkdownChunk.blank());
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
                out.add(MarkdownChunk.markdown(line));
            }
            return out;
        }

        if (line.indexOf('|') >= 0) pendingTableHeader = line;
        else out.add(MarkdownChunk.markdown(line));
        return out;
    }

    List<MarkdownChunk> flush() {
        var out = new ArrayList<MarkdownChunk>();
        flushTableInto(out);
        flushPendingHeaderInto(out);
        return out;
    }

    void clear() {
        table.setLength(0);
        pendingTableHeader = null;
    }

    private boolean inTable() {
        return !table.isEmpty();
    }

    private void appendTableLine(String line) {
        if (!table.isEmpty()) table.append('\n');
        table.append(line);
    }

    private void flushTableInto(List<MarkdownChunk> out) {
        if (!inTable()) return;
        out.add(MarkdownChunk.markdown(table.toString()));
        table.setLength(0);
    }

    private void flushPendingHeaderInto(List<MarkdownChunk> out) {
        if (pendingTableHeader == null) return;
        out.add(MarkdownChunk.markdown(pendingTableHeader));
        pendingTableHeader = null;
    }

    private static boolean isTableSeparator(String stripped) {
        if (stripped.indexOf('|') < 0) return false;
        String s = stripped;
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        for (String cell : s.split("\\|", -1)) {
            if (!TABLE_SEPARATOR_CELL.matcher(cell.strip()).matches()) return false;
        }
        return true;
    }
}
