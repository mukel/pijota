package ai.pijota.tui;

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
import java.util.Optional;

final class MarkdownTableRenderer {
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(),
                                    StrikethroughExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private record Cell(String text, TableCell.Alignment alignment) {
    }

    private final AnsiTheme theme;
    private final int width;

    private MarkdownTableRenderer(AnsiTheme theme, int width) {
        this.theme = theme;
        this.width = width;
    }

    static Optional<List<StyledLine>> renderIfTable(String markdown, AnsiTheme theme, int width) {
        if (!couldBeTable(markdown))
            return Optional.empty();
        var document = (Document) PARSER.parse(markdown);
        if (!(document.getFirstChild() instanceof TableBlock table) || document.getFirstChild().getNext() != null) {
            return Optional.empty();
        }
        return Optional.of(new MarkdownTableRenderer(theme, width).render(table));
    }

    static boolean couldBeTable(String markdown) {
        return markdown.indexOf('|') >= 0 && markdown.indexOf('\n') >= 0;
    }

    private List<StyledLine> render(TableBlock table) {
        var rows = tableRows(table);
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0)
            return List.of();
        int[] widths = columnWidths(rows, cols);
        fitWidths(widths);

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
        String text = cell.isHeader() ? theme.styled("bold", renderInlineChildren(cell)) : renderInlineChildren(cell);
        return new Cell(text, cell.getAlignment());
    }

    private String renderInlineChildren(Node parent) {
        var out = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            out.append(renderInline(child));
        }
        return out.toString();
    }

    private String renderInline(Node node) {
        if (node instanceof Text text)
            return applyTextMarks(text.getLiteral());
        if (node instanceof Code code)
            return delim("`", "inlineCode", code.getLiteral(), "`");
        if (node instanceof Emphasis em)
            return delim("*", "italic", renderInlineChildren(em), "*");
        if (node instanceof StrongEmphasis strong)
            return delim("**", "bold", renderInlineChildren(strong), "**");
        if (node instanceof Strikethrough strike)
            return delim("~~", "strikethrough", renderInlineChildren(strike), "~~");
        if (node instanceof Link link)
            return renderLink(link);
        if (node instanceof Image image)
            return renderImage(image);
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak)
            return " ";
        if (node instanceof HtmlInline html)
            return styled("dim", html.getLiteral());
        return renderInlineChildren(node);
    }

    private String applyTextMarks(String text) {
        var sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            Mark mark = nextMark(text, i);
            if (mark == null) {
                sb.append(styled("text", text.substring(i)));
                break;
            }
            if (mark.start() > i)
                sb.append(styled("text", text.substring(i, mark.start())));
            int contentStart = mark.start() + mark.marker().length();
            int end = text.indexOf(mark.marker(), contentStart);
            if (end < 0) {
                sb.append(styled("text", text.substring(mark.start())));
                break;
            }
            sb.append(styled("dim", mark.marker()));
            sb.append(styled(mark.slot(), text.substring(contentStart, end)));
            sb.append(styled("dim", mark.marker()));
            i = end + mark.marker().length();
        }
        return sb.toString();
    }

    private record Mark(int start, String marker, String slot) {
    }

    private static Mark nextMark(String text, int from) {
        Mark best = null;
        for (var mark : List.of(new Mark(text.indexOf("==", from), "==", "highlight"),
                                        new Mark(text.indexOf("++", from), "++", "diffAdd"),
                                        new Mark(text.indexOf("--", from), "--", "diffDelete"))) {
            if (mark.start() >= 0 && (best == null || mark.start() < best.start()))
                best = mark;
        }
        return best;
    }

    private String renderLink(Link link) {
        String text = renderInlineChildren(link);
        String url = link.getDestination();
        String bracketed = styled("dim", "[") + styled("mdLink", text) + styled("dim", "]");
        if (url == null || url.isBlank() || url.indexOf(' ') >= 0)
            return bracketed;
        String visible = bracketed + styled("dim", "(") + styled("mdLinkUrl", url) + styled("dim", ")");
        return "\033]8;;" + url + "\033\\" + visible + "\033]8;;\033\\";
    }

    private String renderImage(Image image) {
        String alt = renderInlineChildren(image);
        String label = alt.isEmpty() ? "image" : alt;
        String url = image.getDestination() != null ? image.getDestination() : "";
        String visible = styled("dim", "![" + label + "](" + url + ")");
        if (url.isBlank() || url.indexOf(' ') >= 0)
            return visible;
        return "\033]8;;" + url + "\033\\" + visible + "\033]8;;\033\\";
    }

    private String delim(String open, String slot, String inner, String close) {
        return styled("dim", open) + styled(slot, inner) + styled("dim", close);
    }

    private String styled(String slot, String text) {
        return text == null || text.isEmpty() ? "" : theme.styled(slot, text);
    }

    private String separator(int[] widths) {
        var out = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0)
                out.append("─┼─");
            out.append("─".repeat(widths[i]));
        }
        return theme.styled("hr", out.toString());
    }

    private void fitWidths(int[] widths) {
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

    private static List<String> wrapStyled(String text, int width) {
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
