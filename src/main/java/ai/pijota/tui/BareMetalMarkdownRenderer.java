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
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

final class BareMetalMarkdownRenderer {
    private static final List<Extension> EXTENSIONS = List.of(
        TablesExtension.create(),
        TaskListItemsExtension.create(),
        StrikethroughExtension.create()
    );

    private record Cell(String text, TableCell.Alignment alignment) {}

    private AnsiTheme theme;
    private final String plainSlot;
    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private int width = 80;

    BareMetalMarkdownRenderer(AnsiTheme theme, String plainSlot) {
        this.theme = theme;
        this.plainSlot = plainSlot;
    }

    void setTheme(AnsiTheme theme) { this.theme = theme; }
    void setWidth(int width) { if (width > 20) this.width = width; }

    // ---- public API -------------------------------------------

    List<StyledLine> render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return List.of();
        var document = (Document) parser.parse(markdown);
        var out = new ArrayList<StyledLine>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNext())
            renderBlock(child, out, 0);
        return out;
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

    // ---- block dispatch ---------------------------------------

    private void renderBlock(Node node, List<StyledLine> out, int listLevel) {
        if (node instanceof Heading heading)      renderHeading(heading, out);
        else if (node instanceof Paragraph p)      renderParagraph(p, out);
        else if (node instanceof BlockQuote quote) renderQuote(quote, out, listLevel);
        else if (node instanceof ThematicBreak)    renderRule(out);
        else if (node instanceof FencedCodeBlock f)  renderCodeBlock(f.getInfo(), f.getLiteral(), out);
        else if (node instanceof IndentedCodeBlock c) renderIndentedCode(c.getLiteral(), out);
        else if (node instanceof HtmlBlock html)   renderHtmlBlock(html, out);
        else if (node instanceof ListBlock list)   renderList(list, out, listLevel);
        else if (node instanceof TableBlock table) out.addAll(renderTable(table));
        else renderChildren(node, out, listLevel);
    }

    private void renderChildren(Node node, List<StyledLine> out, int listLevel) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext())
            renderBlock(child, out, listLevel);
    }

    // ---- block renderers (bare-metal) ------------------------

    private void renderHeading(Heading heading, List<StyledLine> out) {
        String hashes = "#".repeat(heading.getLevel());
        String text = renderInlineChildren(heading);
        out.add(StyledLine.of(""));
        if (heading.getLevel() <= 1) {
            emitWrapped(slotStyled("heading", hashes + " " + text), "", width, out);
            out.add(styledLine("hr", "━".repeat(Math.max(3, Math.min(width - 1,
                AnsiWidth.visibleWidth(hashes + " " + text))))));
        } else {
            emitWrapped(slotStyled("dim", hashes + " ") + slotStyled("heading", text), "", width, out);
        }
    }

    private void renderParagraph(Paragraph paragraph, List<StyledLine> out) {
        String text = renderInlineChildren(paragraph);
        if (plainSlot != null && !text.isBlank()) text = slotStyled(plainSlot, text);
        if (text.isBlank()) return;
        if (!out.isEmpty() && !out.getLast().text().isEmpty()) out.add(StyledLine.of(""));
        emitWrapped(text, "", width, out);
    }

    private void renderQuote(BlockQuote quote, List<StyledLine> out, int listLevel) {
        var inner = new ArrayList<StyledLine>();
        int oldWidth = width;
        try {
            width = Math.max(8, width - 2);
            renderChildren(quote, inner, listLevel);
        } finally {
            width = oldWidth;
        }
        String rail = slotStyled("dim", "│ ");
        for (var line : inner) out.add(StyledLine.of(rail + line.text()));
    }

    private void renderRule(List<StyledLine> out) {
        out.add(StyledLine.of(""));
        out.add(styledLine("dim", "---"));
    }

    private void renderHtmlBlock(HtmlBlock html, List<StyledLine> out) {
        for (String line : html.getLiteral().split("\\R", -1))
            if (!line.isEmpty()) out.add(styledLine("dim", line));
    }

    private void renderIndentedCode(String literal, List<StyledLine> out) {
        for (String line : literal.split("\\R", -1)) {
            if (line.isEmpty()) out.add(StyledLine.of(""));
            else out.add(styledLine("dim", line));
        }
    }

    private void renderList(ListBlock list, List<StyledLine> out, int level) {
        boolean ordered = list instanceof OrderedList;
        int index = ordered ? ((OrderedList) list).getMarkerStartNumber() : 1;
        int markerWidth = computeMarkerWidth(list, level, ordered, index);
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof ListItem item)
                renderListItem(item, out, level, ordered, index++, markerWidth);
        }
    }

    private int computeMarkerWidth(ListBlock list, int level, boolean ordered, int start) {
        boolean hasTask = false;
        int count = 0;
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof ListItem item) {
                count++;
                if (item.getFirstChild() instanceof TaskListItemMarker) hasTask = true;
            }
        }
        int base = ordered
            ? String.valueOf(start + Math.max(0, count - 1)).length() + 1
            : bullet(level).length();
        if (hasTask) base += 1 + 3; // space + "[x]" / "[ ]"
        return base;
    }

    private void renderListItem(ListItem item, List<StyledLine> out, int level,
                                 boolean ordered, int index, int markerWidth) {
        Node first = item.getFirstChild();
        String marker = ordered ? index + "." : bullet(level);

        if (first instanceof TaskListItemMarker task) {
            String checkbox = task.isChecked()
                ? slotStyled("dim", "[") + slotStyled("toolLabel", "x") + slotStyled("dim", "]")
                : slotStyled("dim", "[ ]");
            marker = marker + " " + checkbox;
            first = first.getNext();
        }

        String content = "";
        if (first instanceof Paragraph paragraph) {
            content = renderInlineChildren(paragraph);
            first = first.getNext();
        }

        String indent = " ".repeat(level * 2);
        String markerText = padLeft(marker, markerWidth);
        String prefix = indent + slotStyled("dim", markerText + " ");
        String continuation = indent + " ".repeat(markerWidth + 1);
        emitWrapped(content, prefix, Math.max(8, width - AnsiWidth.visibleWidth(prefix)),
            continuation, out);

        for (Node child = first; child != null; child = child.getNext()) {
            if (child instanceof ListBlock nested) renderList(nested, out, level + 1);
            else renderBlock(child, out, level + 1);
        }
    }

    private String bullet(int level) {
        return switch (level % 3) {
            case 0 -> "•"; case 1 -> "◦"; default -> "▪";
        };
    }

    private void renderCodeBlock(String info, String literal, List<StyledLine> out) {
        out.add(renderFenceOpen(info));
        var highlighter = new SyntaxHighlighter(info == null ? "" : info.strip(), theme);
        String[] lines = literal.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) continue;
            out.addAll(renderCodeLine(lines[i], highlighter));
        }
        out.add(renderFenceClose());
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
        if (node instanceof TaskListItemMarker task)
            return slotStyled("dim", task.isChecked() ? "[x]" : "[ ]");
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
    }

    private static int preferredWidth(String text) {
        int max = 0;
        for (String word : text.split("\\s+"))
            max = Math.max(max, AnsiWidth.visibleWidth(word));
        return Math.max(max, Math.min(24, AnsiWidth.visibleWidth(text)));
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

    private void emitWrapped(String text, String prefix, int wrapWidth, List<StyledLine> out) {
        emitWrapped(text, prefix, wrapWidth, "", out);
    }

    private void emitWrapped(String text, String firstPrefix, int wrapWidth,
                              String continuationPrefix, List<StyledLine> out) {
        var lines = wrapStyled(text, wrapWidth);
        if (lines.isEmpty()) { out.add(StyledLine.of(firstPrefix)); return; }
        out.add(StyledLine.of(firstPrefix + lines.getFirst()));
        for (int i = 1; i < lines.size(); i++)
            out.add(StyledLine.of(continuationPrefix + lines.get(i)));
    }

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

    private static String padLeft(String text, int width) {
        return spaces(width - AnsiWidth.visibleWidth(text)) + text;
    }

    private static String spaces(int count) {
        return count <= 0 ? "" : " ".repeat(count);
    }
}
