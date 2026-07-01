package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class MarkdownTableRenderer {
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(),
                                    StrikethroughExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private record Cell(String text, TableCell.Alignment alignment) {
    }

    private final AnsiTheme theme;
    private final int width;
    private final MarkdownInline inline;

    MarkdownTableRenderer(AnsiTheme theme, int width) {
        this(theme, width, false);
    }

    MarkdownTableRenderer(AnsiTheme theme, int width, boolean strip) {
        this.theme = theme;
        this.width = width;
        this.inline = new MarkdownInline(theme, strip);
    }

    static Optional<List<StyledLine>> renderIfTable(String markdown, AnsiTheme theme, int width) {
        return renderIfTable(markdown, theme, width, false);
    }

    /** {@code strip}: consume inline delimiters in cells (rich look) vs. show them dimmed (barebones). */
    static Optional<List<StyledLine>> renderIfTable(String markdown, AnsiTheme theme, int width, boolean strip) {
        if (!couldBeTable(markdown))
            return Optional.empty();
        // Dedent first: a table indented 4+ spaces (e.g. nested under a list item) parses as an indented code block,
        // not a table. Stripping the common leading indent lets CommonMark see the table.
        var document = (Document) PARSER.parse(dedent(markdown));
        if (!(document.getFirstChild() instanceof TableBlock table) || document.getFirstChild().getNext() != null) {
            return Optional.empty();
        }
        return Optional.of(new MarkdownTableRenderer(theme, width, strip).render(table));
    }

    /** Render a table node already parsed by a caller (e.g. a table nested inside a blockquote). */
    static List<StyledLine> renderTable(TableBlock table, AnsiTheme theme, int width, boolean strip) {
        return new MarkdownTableRenderer(theme, width, strip).render(table);
    }

    static String dedent(String markdown) {
        int min = Integer.MAX_VALUE;
        for (String line : markdown.split("\n", -1)) {
            if (line.isBlank())
                continue;
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'))
                i++;
            min = Math.min(min, i);
        }
        if (min <= 0 || min == Integer.MAX_VALUE)
            return markdown;
        var sb = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (!sb.isEmpty())
                sb.append('\n');
            sb.append(line.length() >= min ? line.substring(min) : line.stripLeading());
        }
        return sb.toString();
    }

    static boolean couldBeTable(String markdown) {
        return markdown != null && markdown.indexOf('|') >= 0 && markdown.indexOf('\n') >= 0;
    }

    private List<StyledLine> render(TableBlock table) {
        var rows = tableRows(table);
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0)
            return List.of();
        int[] widths = columnWidths(rows, cols);
        fitWidths(widths, width);

        var out = new ArrayList<StyledLine>();
        out.add(StyledLine.of(""));
        for (int r = 0; r < rows.size(); r++) {
            out.addAll(renderRow(rows.get(r), widths));
            if (r == 0 && rows.size() > 1)
                out.add(StyledLine.of(separator(widths)));
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
                if (col > 0)
                    sb.append(theme.style("hr")).append(" │ ").append(theme.reset());
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
                if (child instanceof TableCell cell)
                    cells.add(renderCell(cell));
            }
            rows.add(cells);
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext())
            collectRows(child, rows);
    }

    private Cell renderCell(TableCell cell) {
        String content = inline.children(cell, "text");
        return new Cell(cell.isHeader() ? theme.styled("bold", content) : content, cell.getAlignment());
    }

    /** The GFM {@code ==}/{@code ++}/{@code --} text marks rendered inside cells (KEEP mode); exposed for tests. */
    String applyTextMarks(String text) {
        return inline.applyTextMarks(text);
    }

    String separator(int[] widths) {
        var out = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0)
                out.append("─┼─");
            out.append("─".repeat(widths[i]));
        }
        return theme.styled("hr", out.toString());
    }

    static void fitWidths(int[] widths, int width) {
        int cols = widths.length;
        int available = Math.max(cols * 5, width - ((cols - 1) * 3));
        for (int i = 0; i < widths.length; i++)
            widths[i] = Math.max(3, widths[i]);
        while (sum(widths) > available) {
            int idx = widest(widths);
            if (widths[idx] <= 5)
                break;
            widths[idx]--;
        }
        while (sum(widths) < available) {
            for (int i = 0; i < widths.length && sum(widths) < available; i++)
                widths[i]++;
        }
    }

    static int preferredWidth(String text) {
        return AnsiWidth.visibleWidth(text);
    }

    static int sum(int[] widths) {
        int total = 0;
        for (int w : widths)
            total += w;
        return total;
    }

    static int widest(int[] widths) {
        int idx = 0;
        for (int i = 1; i < widths.length; i++)
            if (widths[i] > widths[idx])
                idx = i;
        return idx;
    }

    static String align(String text, int width, TableCell.Alignment alignment) {
        int pad = width - AnsiWidth.visibleWidth(text);
        if (pad <= 0)
            return text;
        if (alignment == TableCell.Alignment.RIGHT)
            return spaces(pad) + text;
        if (alignment == TableCell.Alignment.CENTER) {
            int left = pad / 2;
            return spaces(left) + text + spaces(pad - left);
        }
        return text + spaces(pad);
    }

    static List<String> wrapStyled(String text, int width) {
        if (text == null || text.isEmpty())
            return List.of("");
        if (AnsiWidth.visibleWidth(text) <= width)
            return List.of(text);
        return AnsiWidth.wrapPreservingAnsi(text, Math.max(1, width));
    }

    static String spaces(int count) {
        return count <= 0 ? "" : " ".repeat(count);
    }
}
