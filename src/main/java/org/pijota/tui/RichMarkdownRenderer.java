package org.pijota.tui;

import org.pijota.modes.SyntaxHighlighter;
import org.pijota.theme.AnsiTheme;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Rich terminal markdown, styled like pi/opencode: the delimiters ({@code #}, {@code **}, {@code `}, {@code >}, ...)
 * are <em>consumed</em>, and the text is styled instead. Parses each committed chunk with CommonMark; inline styling
 * (delimiter-stripped) is shared with table cells via {@link MarkdownInline}, so prose and tables look consistent.
 * Tables delegate to {@link MarkdownTableRenderer}; fenced code is handled upstream in the formatter.
 */
final class RichMarkdownRenderer implements MarkdownRenderer {
    private static final Parser PARSER = Parser.builder()
                                    .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
                                    .build();
    private static final String BOLD = "\033[1m", UNDERLINE = "\033[4m";

    private AnsiTheme theme;
    private MarkdownInline inline;
    private int width = 80;

    RichMarkdownRenderer(AnsiTheme theme) {
        this.theme = theme;
        this.inline = new MarkdownInline(theme, true);
    }

    @Override
    public void setTheme(AnsiTheme theme) {
        this.theme = theme;
        this.inline = new MarkdownInline(theme, true);
    }

    @Override
    public void setWidth(int width) {
        this.width = Math.max(1, width);
    }

    @Override
    public List<StyledLine> render(String markdown, String defaultTextSlot, boolean defaultItalic) {
        if (markdown == null || markdown.isEmpty())
            return List.of();
        // The model's text is not pijota's either: an ESC in it commands the terminal exactly as one from a
        // file does, and a model quoting a poisoned file or page back to the reader carries whatever was in
        // it. Escaped once here, at the boundary where untrusted markdown becomes terminal bytes.
        markdown = TerminalText.printableMarkdown(markdown);
        var table = MarkdownTableRenderer.renderIfTable(markdown, theme, width, true);
        if (table.isPresent())
            return table.get();
        String base = defaultTextSlot == null || defaultTextSlot.isBlank() ? "text" : defaultTextSlot;
        // Dedent before parsing: a line indented 4+ spaces (a deeply nested list item streamed on its own) parses as
        // an indented code block, not a list. Strip the common indent, parse, then re-apply it to the output so the
        // nesting stays visible.
        int indent = MarkdownTableRenderer.commonIndent(markdown);
        var blocks = new ArrayList<StyledLine>();
        var doc = PARSER.parse(indent == 0 ? markdown : MarkdownTableRenderer.stripIndent(markdown, indent));
        for (Node block = doc.getFirstChild(); block != null; block = block.getNext())
            renderBlock(block, base, defaultItalic, 0, blocks);
        if (blocks.isEmpty()) // whitespace-only or unparsed, echo it styled with the base slot
            return List.of(StyledLine.of(theme.styled(base, markdown)));
        if (indent == 0)
            return blocks;
        String pad = " ".repeat(indent);
        var out = new ArrayList<StyledLine>(blocks.size());
        for (StyledLine l : blocks)
            out.add(l.text().isBlank() ? l : StyledLine.of(pad + l.text()));
        return out;
    }

    /**
     * The list marker. U+2981 rather than the U+2022 bullet: heavier, so a list reads as a list at a glance, and still
     * round, which a square is not.
     *
     * <p>
     * Not U+25CF, which {@code ToolGlyphs.MUTATE} already spends on "a tool that changes something" -- the same glyph
     * would then mean two things on one screen. Not U+2B24, which is heavier still and thinner on font coverage, a poor
     * trade for a character that appears on every list. One cell wide in both the width table and a terminal, checked,
     * because a marker that measures wrong misaligns every list it opens.
     */
    private static final String LIST_BULLET = "\u2981";

    private void renderBlock(Node node, String base, boolean italic, int depth, List<StyledLine> out) {
        if (node instanceof Heading h) {
            out.add(StyledLine.of(heading(h.getLevel(), MarkdownInline.plainText(h))));
        } else if (node instanceof Paragraph p) {
            out.add(StyledLine.of(inline.children(p, base, italic)));
        } else if (node instanceof BulletList list) {
            for (Node item = list.getFirstChild(); item != null; item = item.getNext())
                renderItem(item, base, italic, depth, LIST_BULLET, out);
        } else if (node instanceof OrderedList list) {
            int n = list.getMarkerStartNumber() != null ? list.getMarkerStartNumber() : 1;
            for (Node item = list.getFirstChild(); item != null; item = item.getNext(), n++)
                renderItem(item, base, italic, depth, n + ".", out);
        } else if (node instanceof BlockQuote q) {
            // Render children as blocks so nested quotes add another bar; prefix every produced line with the bar.
            var inner = new ArrayList<StyledLine>();
            for (Node child = q.getFirstChild(); child != null; child = child.getNext())
                renderBlock(child, "blockquote", true, 0, inner);
            String bar = theme.styled("blockquote", "│ ");
            if (inner.isEmpty())
                out.add(StyledLine.of(theme.styled("blockquote", "│"))); // empty quote line ("> " on its own)
            else
                for (StyledLine line : inner)
                    out.add(StyledLine.of(bar + line.text()));
        } else if (node instanceof ThematicBreak) {
            // Same glyph, width, and colour as table divisions (the "hr" slot: soft grey, thin), and one column short
            // of the terminal for the same reason as PromptRegion.rule: a row that fills the last column is flagged
            // wrapped, and this one lands in scrollback, where a later resize would split it permanently.
            out.add(StyledLine.of(theme.styled("hr", "─".repeat(Math.max(3, width - 1)))));
        } else if (node instanceof TableBlock table) {
            out.addAll(MarkdownTableRenderer.renderTable(table, theme, width, true)); // table nested in a quote/list
        } else if (node instanceof FencedCodeBlock code) {
            renderCode(code.getLiteral(), code.getInfo(), out);
        } else if (node instanceof IndentedCodeBlock code) {
            renderCode(code.getLiteral(), "", out);
        } else {
            String s = inline.children(node, base, italic);
            if (!s.isEmpty())
                out.add(StyledLine.of(s));
        }
    }

    private void renderCode(String literal, String info, List<StyledLine> out) {
        String lang = StreamingMarkdownFormatter.fenceLanguage(info); // normalize + alias (js->javascript, ...)
        SyntaxHighlighter hl = lang.isBlank() ? null : new SyntaxHighlighter(lang, theme);
        String[] rows = literal.split("\n", -1);
        int end = rows.length > 0 && rows[rows.length - 1].isEmpty() ? rows.length - 1 : rows.length; // trailing \n
        for (int i = 0; i < end; i++)
            out.add(StyledLine.of(hl != null ? hl.highlightLine(rows[i]) : theme.styled("codeBody", rows[i])));
    }

    private void renderItem(Node item, String base, boolean italic, int depth, String marker, List<StyledLine> out) {
        if (!(item instanceof ListItem))
            return;
        String indent = "  ".repeat(depth);
        String box = checkbox(item); // task-list marker replaces the bullet, GitHub-style
        String lead = indent + (box != null ? box : theme.styled("mdListBullet", marker)) + " ";
        boolean first = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof BulletList || child instanceof OrderedList) {
                renderBlock(child, base, italic, depth + 1, out); // nested list
            } else if (first) {
                out.add(StyledLine.of(lead + inline.children(child, base, italic)));
                first = false;
            } else {
                String s = inline.children(child, base, italic);
                if (!s.isEmpty())
                    out.add(StyledLine.of(indent + "  " + s)); // continuation, aligned under the text
            }
        }
        if (first)
            out.add(StyledLine.of(lead.stripTrailing()));
    }

    /** If the item opens with a {@code [x]}/{@code [ ]}/{@code [~]} task marker, strip it and return a styled box. */
    private String checkbox(Node item) {
        if (!(item.getFirstChild() instanceof Paragraph p) || !(p.getFirstChild() instanceof Text t))
            return null;
        String lit = t.getLiteral();
        if (lit.length() < 3 || lit.charAt(0) != '[' || lit.charAt(2) != ']')
            return null;
        String glyph = switch (lit.charAt(1)) {
            case 'x', 'X' -> theme.styled("diffAdd", "✓");
            case ' ' -> theme.styled("dim", "☐");
            case '~' -> theme.styled("statusWarn", "◐");
            default -> null;
        };
        if (glyph == null)
            return null;
        t.setLiteral(lit.substring(lit.length() > 3 && lit.charAt(3) == ' ' ? 4 : 3)); // drop "[x] " from the text
        return glyph;
    }

    private String heading(int level, String text) {
        if (!theme.enabled || text.isEmpty())
            return text;
        var sb = new StringBuilder();
        sb.append(theme.style("heading")).append(BOLD);
        if (level == 1)
            sb.append(UNDERLINE);
        return sb.append(text).append(theme.reset()).toString();
    }
}
