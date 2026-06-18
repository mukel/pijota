package ai.pijota.telegram;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Renders the agent's Markdown into the small subset of HTML that Telegram's {@code parse_mode=HTML} accepts
 * (<a href="https://core.telegram.org/bots/api#html-style">b/i/u/s/code/pre/a/blockquote</a>). Telegram has no
 * headings, lists or tables, so headings degrade to bold, list bullets to plain bullet/number lines, and tables to a
 * monospace preformatted block. Parsing always yields well-formed output — even a half-streamed message with an
 * unclosed bold marker or code fence — because CommonMark auto-closes open nodes, so the result is always safe to send.
 */
final class TelegramHtml {
    private static final Parser PARSER = Parser.builder()
                                    .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
                                    .build();
    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\n{3,}");

    private TelegramHtml() {}

    /** Converts a Markdown document to Telegram-flavored HTML. */
    static String toHtml(String markdown) {
        var sb = new StringBuilder();
        renderBlocks(PARSER.parse(markdown), sb);
        // collapse the blank-line runs that block separators leave behind
        return BLANK_LINE_RUN.matcher(sb.toString().strip()).replaceAll("\n\n");
    }

    /** Escapes the three characters Telegram requires escaped in HTML text/attribute content. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Wraps {@code content} in a Telegram HTML element, e.g. {@code wrap("b", "hi")} → {@code "<b>hi</b>"}. */
    private static String wrap(String tag, String content) {
        return "<" + tag + ">" + content + "</" + tag + ">";
    }

    // ---- block level ----

    private static void renderBlocks(Node parent, StringBuilder sb) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext())
            renderBlock(n, sb);
    }

    private static void renderBlock(Node node, StringBuilder sb) {
        switch (node) {
            case Paragraph p -> sb.append(inline(p)).append("\n\n");
            case Heading h -> sb.append(wrap("b", inline(h))).append("\n\n");
            case FencedCodeBlock c -> sb.append(wrap("pre", escape(c.getLiteral()))).append("\n\n");
            case IndentedCodeBlock c -> sb.append(wrap("pre", escape(c.getLiteral()))).append("\n\n");
            case ThematicBreak ignored -> sb.append("──────\n\n");
            case BlockQuote q -> {
                var inner = new StringBuilder();
                renderBlocks(q, inner);
                sb.append(wrap("blockquote", inner.toString().strip())).append("\n\n");
            }
            case BulletList list -> renderList(list, sb, null);
            case OrderedList list -> renderList(list, sb, list.getMarkerStartNumber());
            case TableBlock table -> renderTable(table, sb);
            case HtmlBlock html -> sb.append(escape(html.getLiteral())).append("\n\n");
            default -> renderBlocks(node, sb); // unknown container: descend
        }
    }

    private static void renderList(Node list, StringBuilder sb, Integer start) {
        int n = start == null ? 0 : start;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            String marker = start == null ? "• " : (n++) + ". ";
            sb.append(marker).append(inline(item).strip()).append('\n');
        }
        sb.append('\n');
    }

    // A monospace <pre> grid wider than a phone's line turns into a horizontally-scrolling "raw file" in Telegram, so
    // wider tables are rendered as a vertical list of records instead. ponytail: a fixed char threshold, not the
    // viewport (Telegram doesn't expose it) — tuned for mobile, where readability matters most.
    private static final int MAX_PRE_TABLE_WIDTH = 40;

    private static void renderTable(TableBlock table, StringBuilder sb) {
        var rows = new ArrayList<List<String>>();
        collectRows(table, rows);
        if (rows.isEmpty())
            return;
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[cols];
        for (var row : rows)
            for (int i = 0; i < row.size(); i++)
                widths[i] = Math.max(widths[i], row.get(i).length());

        int gridWidth = (cols - 1) * 3 + IntStream.of(widths).sum(); // the cells plus the " | " separators
        if (gridWidth > MAX_PRE_TABLE_WIDTH)
            renderTableAsRecords(rows, sb);
        else
            renderGridTable(rows, widths, sb);
    }

    private static void renderGridTable(List<List<String>> rows, int[] widths, StringBuilder sb) {
        sb.append("<pre>");
        for (int r = 0; r < rows.size(); r++) {
            sb.append(escape(padJoin(rows.get(r), widths))).append('\n');
            if (r == 0) // header underline
                sb.append(escape(separator(widths))).append('\n');
        }
        sb.append("</pre>\n\n");
    }

    /**
     * Renders a too-wide table as a vertical list of records, which wraps cleanly on a phone. Each data row becomes a
     * block titled by its first cell (bolded); a two-column table shows the second cell on its own line, while a table
     * with three or more columns labels each remaining cell with its header ({@code Header: value}).
     */
    private static void renderTableAsRecords(List<List<String>> rows, StringBuilder sb) {
        List<String> header = rows.get(0);
        boolean labelled = header.size() > 2; // a 2-column value is self-explanatory; 3+ columns get a "Header: " label
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.isEmpty())
                continue;
            sb.append(wrap("b", escape(row.get(0)))).append('\n');
            for (int i = 1; i < row.size(); i++) {
                String value = row.get(i);
                if (value.isBlank())
                    continue;
                String label = labelled && i < header.size() && !header.get(i).isBlank()
                                                ? escape(header.get(i)) + ": "
                                                : "";
                sb.append(label).append(escape(value)).append('\n');
            }
            sb.append('\n');
        }
    }

    private static String padJoin(List<String> row, int[] widths) {
        var line = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0)
                line.append(" | ");
            String cell = i < row.size() ? row.get(i) : "";
            line.append(cell).append(" ".repeat(widths[i] - cell.length()));
        }
        return line.toString().stripTrailing();
    }

    private static String separator(int[] widths) {
        var line = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0)
                line.append("-+-");
            line.append("-".repeat(widths[i]));
        }
        return line.toString();
    }

    private static void collectRows(Node node, List<List<String>> rows) {
        if (node instanceof TableRow row) {
            var cells = new ArrayList<String>();
            for (Node c = row.getFirstChild(); c != null; c = c.getNext())
                if (c instanceof TableCell cell)
                    cells.add(plain(cell).strip());
            rows.add(cells);
            return;
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNext())
            collectRows(c, rows);
    }

    // ---- inline level ----

    private static String inline(Node parent) {
        var sb = new StringBuilder();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext())
            sb.append(renderInline(n));
        return sb.toString();
    }

    private static String renderInline(Node node) {
        return switch (node) {
            case Text t -> escape(t.getLiteral());
            case StrongEmphasis ignored -> wrap("b", inline(node));
            case Emphasis ignored -> wrap("i", inline(node));
            case Strikethrough ignored -> wrap("s", inline(node));
            case Code c -> wrap("code", escape(c.getLiteral()));
            case Link l -> renderLink(l.getDestination(), inline(l));
            case Image img -> {
                String alt = inline(img).strip();
                yield renderLink(img.getDestination(), alt.isEmpty() ? "image" : alt);
            }
            case SoftLineBreak ignored -> "\n";
            case HardLineBreak ignored -> "\n";
            case HtmlInline h -> escape(h.getLiteral()); // Telegram forbids arbitrary tags: show as literal text
            case Paragraph ignored -> inline(node) + "\n"; // list-item paragraphs
            default -> inline(node);
        };
    }

    /**
     * Plain (tag-free) inline text, for table cells where HTML markup isn't allowed inside an outer {@code 
     * 
     * 
    
    <pre>
     * }.
     */
    private static String plain(Node parent) {
        var sb = new StringBuilder();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Text t)
                sb.append(t.getLiteral());
            else if (n instanceof Code c)
                sb.append(c.getLiteral());
            else if (n instanceof SoftLineBreak || n instanceof HardLineBreak)
                sb.append(' ');
            else
                sb.append(plain(n));
        }
        return sb.toString();
    }

    private static String renderLink(String url, String text) {
        if (url == null || url.isBlank() || url.indexOf('"') >= 0)
            return text; // unusable href: keep the visible text only
        return "<a href=\"" + escape(url) + "\">" + text + "</a>";
    }
}
