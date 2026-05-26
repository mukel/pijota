package ai.pijota.tui;

import ai.pijota.modes.SyntaxHighlighter;
import ai.pijota.theme.AnsiTheme;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

final class TerminalMarkdownRenderer {
    private static final List<Extension> EXTENSIONS = List.of(
        TablesExtension.create(),
        StrikethroughExtension.create()
    );

    private record Cell(String text, TableCell.Alignment alignment) {}

    private AnsiTheme theme;
    private final String plainSlot;
    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private int width = 80;

    TerminalMarkdownRenderer(AnsiTheme theme, String plainSlot) {
        this.theme = theme;
        this.plainSlot = plainSlot;
    }

    void setTheme(AnsiTheme theme) { this.theme = theme; }
    void setWidth(int width) { if (width > 20) this.width = width; }

    // ---- public API -------------------------------------------

    List<StyledLine> render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return List.of();
        var document = (Document) parser.parse(markdown);
        if (isTableDocument(document)) return renderTable((TableBlock) document.getFirstChild());
        return renderSource(markdown);
    }

    private static boolean isTableDocument(Document document) {
        return document.getFirstChild() instanceof TableBlock && document.getFirstChild().getNext() == null;
    }

    StyledLine renderFenceOpen(String info) {
        return styledLine("dim", "```" + (info == null || info.isBlank() ? "" : info.strip()));
    }

    StyledLine renderFenceClose() {
        return styledLine("dim", "```");
    }

    List<StyledLine> renderCodeLine(String line, SyntaxHighlighter highlighter) {
        String highlighted = highlighter == null ? line : highlighter.highlightLine(line);
        if (highlighted.isEmpty()) return List.of(StyledLine.of(""));
        int codeWidth = Math.max(1, width - 4);
        if (AnsiWidth.visibleWidth(highlighted) <= codeWidth) return List.of(StyledLine.of(highlighted));
        return AnsiWidth.wrapPreservingAnsi(highlighted, codeWidth).stream().map(StyledLine::of).toList();
    }

    private List<StyledLine> renderSource(String markdown) {
        var out = new ArrayList<StyledLine>();
        for (String line : markdown.split("\\R", -1)) out.add(StyledLine.of(highlightSourceLine(line)));
        return out;
    }

    private String highlightSourceLine(String line) {
        if (line.isEmpty()) return line;
        int prefixEnd = markdownPrefixEnd(line);
        String prefix = prefixEnd == 0 ? "" : highlightPrefix(line.substring(0, prefixEnd));
        String body = line.substring(prefixEnd);
        String highlightedBody = highlightInlineSource(body);
        if (plainSlot != null && prefixEnd == 0 && highlightedBody.equals(body)) return slotStyled(plainSlot, line);
        return prefix + highlightedBody;
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
        String slot = prefix.startsWith("#", i) ? "heading" : "dim";
        return prefix.substring(0, i) + slotStyled(slot, prefix.substring(i));
    }

    private String highlightInlineSource(String line) {
        var out = new StringBuilder();
        for (int i = 0; i < line.length();) {
            int next = appendDelimited(line, i, "`", "inlineCode", out);
            if (next < 0) next = appendDelimited(line, i, "**", "bold", out);
            if (next < 0) next = appendDelimited(line, i, "~~", "strikethrough", out);
            if (next >= 0) { i = next; continue; }
            out.append(line.charAt(i++));
        }
        return out.toString();
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

    // ---- inline renderer (bare-metal: dim delimiters) ---------

    private String renderInlineChildren(Node parent) {
        return renderInlineChildren(parent, null);
    }

    private String renderInlineChildren(Node parent, Node skip) {
        var out = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child != skip) out.append(renderInline(child));
        }
        return out.toString();
    }

    private String renderInline(Node node) {
        if (node instanceof Text text) {
            String literal = text.getLiteral();
            literal = applyMarks(literal, "==", "highlight");
            literal = applyMarks(literal, "++", "diffAdd");
            literal = applyMarks(literal, "--", "diffDelete");
            return literal;
        }
        if (node instanceof Code code)
            return delim("`", "inlineCode", code.getLiteral(), "`");
        if (node instanceof Emphasis em)
            return delim("*", "italic", renderInlineChildren(em), "*");
        if (node instanceof StrongEmphasis strong)
            return delim("**", "bold", renderInlineChildren(strong), "**");
        if (node instanceof Strikethrough strike)
            return delim("~~", "strikethrough", renderInlineChildren(strike), "~~");
        if (node instanceof Link link) return renderLink(link);
        if (node instanceof Image image) return renderImage(image);
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) return " ";
        if (node instanceof HtmlInline html) return slotStyled("dim", html.getLiteral());
        return renderInlineChildren(node, null);
    }

    private String delim(String open, String slot, String inner, String close) {
        return slotStyled("dim", open) + slotStyled(slot, inner) + slotStyled("dim", close);
    }

    private String applyMarks(String text, String marker, String slot) {
        var sb = new StringBuilder();
        int i = 0;
        int mlen = marker.length();
        while (i < text.length()) {
            int start = text.indexOf(marker, i);
            if (start < 0) { sb.append(text.substring(i)); break; }
            sb.append(text, i, start);
            int end = text.indexOf(marker, start + mlen);
            if (end < 0) { sb.append(text.substring(start)); break; }
            String inner = text.substring(start + mlen, end);
            sb.append(slotStyled("dim", marker));
            sb.append(slotStyled(slot, inner));
            sb.append(slotStyled("dim", marker));
            i = end + mlen;
        }
        return sb.toString();
    }

    private String renderLink(Link link) {
        String text = renderInlineChildren(link);
        String url = link.getDestination();
        String bracketed = slotStyled("dim", "[") + slotStyled("mdLink", text) + slotStyled("dim", "]");
        if (url == null || url.isBlank() || url.indexOf(' ') >= 0) return bracketed;
        String visible = bracketed + slotStyled("dim", "(") + slotStyled("statusText", url) + slotStyled("dim", ")");
        return "\033]8;;" + url + "\033\\" + visible + "\033]8;;\033\\";
    }

    private String renderImage(Image image) {
        String alt = renderInlineChildren(image);
        String label = alt.isEmpty() ? "image" : alt;
        String url = image.getDestination() != null ? image.getDestination() : "";
        String visible = slotStyled("dim", "![" + label + "](" + url + ")");
        if (url.isBlank() || url.indexOf(' ') >= 0) return visible;
        return "\033]8;;" + url + "\033\\" + visible + "\033]8;;\033\\";
    }

    // ---- table rendering (preserved from terminal renderer) ---

    private List<StyledLine> renderTable(TableBlock table) {
        var rows = tableRows(table);
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) return List.of();
        int[] widths = columnWidths(rows, cols);
        fitWidths(widths);

        var out = new ArrayList<StyledLine>();
        out.add(StyledLine.of(""));
        for (int r = 0; r < rows.size(); r++) {
            out.addAll(renderRow(rows.get(r), widths));
            if (r == 0 && rows.size() > 1) out.add(StyledLine.of(separator(widths)));
        }
        out.add(StyledLine.of(""));
        return out;
    }

    private List<StyledLine> renderRow(List<Cell> row, int[] widths) {
        var wrapped = new ArrayList<List<String>>();
        int height = 1;
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.size() ? row.get(i).text() : "";
            List<String> lines = wrapStyled(cell, widths[i]);
            wrapped.add(lines);
            height = Math.max(height, lines.size());
        }
        var out = new ArrayList<StyledLine>();
        for (int line = 0; line < height; line++) {
            var sb = new StringBuilder();
            for (int col = 0; col < widths.length; col++) {
                if (col > 0) sb.append(theme.style("hr")).append(" │ ").append(theme.reset());
                String cell = line < wrapped.get(col).size() ? wrapped.get(col).get(line) : "";
                TableCell.Alignment alignment = col < row.size() ? row.get(col).alignment() : null;
                sb.append(align(cell, widths[col], alignment));
            }
            out.add(StyledLine.of(sb.toString()));
        }
        return out;
    }

    private List<List<Cell>> tableRows(TableBlock table) {
        var rows = new ArrayList<List<Cell>>();
        collectRows(table, rows);
        return rows;
    }

    private int[] columnWidths(List<List<Cell>> rows, int cols) {
        int[] widths = new int[cols];
        for (var row : rows)
            for (int i = 0; i < row.size(); i++)
                widths[i] = Math.max(widths[i], preferredWidth(row.get(i).text()));
        return widths;
    }

    private void collectRows(Node node, List<List<Cell>> rows) {
        if (node instanceof TableRow row) {
            var cells = new ArrayList<Cell>();
            for (Node child = row.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof TableCell cell) cells.add(renderCell(cell));
            }
            rows.add(cells);
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext())
            collectRows(child, rows);
    }

    private Cell renderCell(TableCell cell) {
        String text = cell.isHeader() ? slotStyled("bold", renderInlineChildren(cell))
                                      : renderInlineChildren(cell);
        return new Cell(text, cell.getAlignment());
    }

    private String separator(int[] widths) {
        var out = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) out.append("─┼─");
            out.append("─".repeat(widths[i]));
        }
        return theme.styled("hr", out.toString());
    }

    private void fitWidths(int[] widths) {
        int cols = widths.length;
        int available = Math.max(cols * 5, width - ((cols - 1) * 3));
        for (int i = 0; i < widths.length; i++) widths[i] = Math.max(3, widths[i]);
        while (sum(widths) > available) {
            int idx = widest(widths);
            if (widths[idx] <= 5) break;
            widths[idx]--;
        }
        while (sum(widths) < available) {
            for (int i = 0; i < widths.length && sum(widths) < available; i++)
                widths[i]++;
        }
    }

    private static int preferredWidth(String text) {
        int max = 0;
        for (String word : text.split("\\s+"))
            max = Math.max(max, AnsiWidth.visibleWidth(word));
        return Math.max(max, AnsiWidth.visibleWidth(text));
    }

    private static int sum(int[] widths) {
        int total = 0;
        for (int w : widths) total += w;
        return total;
    }

    private static int widest(int[] widths) {
        int idx = 0;
        for (int i = 1; i < widths.length; i++)
            if (widths[i] > widths[idx]) idx = i;
        return idx;
    }

    private static String align(String text, int width, TableCell.Alignment alignment) {
        int pad = width - AnsiWidth.visibleWidth(text);
        if (pad <= 0) return text;
        if (alignment == TableCell.Alignment.RIGHT) return spaces(pad) + text;
        if (alignment == TableCell.Alignment.CENTER) {
            int left = pad / 2;
            return spaces(left) + text + spaces(pad - left);
        }
        return text + spaces(pad);
    }

    // ---- helpers ----------------------------------------------

    private static List<String> wrapStyled(String text, int width) {
        if (text == null || text.isEmpty()) return List.of("");
        if (AnsiWidth.visibleWidth(text) <= width) return List.of(text);
        return AnsiWidth.wrapPreservingAnsi(text, Math.max(1, width));
    }

    private String slotStyled(String slot, String text) {
        if (text == null || text.isEmpty()) return "";
        return theme.styled(slot, text);
    }

    private StyledLine styledLine(String slot, String text) {
        return StyledLine.of(theme.styled(slot, text));
    }

    private static String spaces(int count) {
        return count <= 0 ? "" : " ".repeat(count);
    }
}
