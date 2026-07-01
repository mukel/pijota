package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;
import ai.pijota.util.Util;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;

import java.util.List;

/**
 * Renders CommonMark inline nodes to a styled string — shared by prose ({@link RichMarkdownRenderer}) and table cells
 * ({@link MarkdownTableRenderer}) so they look identical. Two modes: KEEP (delimiters shown dimmed — the barebones
 * look) and STRIP (delimiters consumed, styled — the rich look). Links use OSC 8 hyperlinks; STRIP additionally shows
 * the URL dimmed when it differs from the link text, so it stays readable on terminals without OSC 8 support.
 */
final class MarkdownInline {
    private static final String BOLD = "\033[1m", ITALIC = "\033[3m", UNDERLINE = "\033[4m", STRIKE = "\033[9m";

    private final AnsiTheme theme;
    private final boolean strip;

    MarkdownInline(AnsiTheme theme, boolean strip) {
        this.theme = theme;
        this.strip = strip;
    }

    /** Render {@code parent}'s inline children; {@code baseSlot} styles plain text (STRIP mode). */
    String children(Node parent, String baseSlot) {
        return children(parent, baseSlot, false);
    }

    String children(Node parent, String baseSlot, boolean italic) {
        var sb = new StringBuilder();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNext())
            node(c, baseSlot, italic, false, false, sb);
        return sb.toString();
    }

    private void node(Node n, String slot, boolean italic, boolean bold, boolean strk, StringBuilder sb) {
        if (n instanceof Text t) {
            sb.append(strip ? marksStrip(t.getLiteral(), slot, italic, bold, strk) : applyTextMarks(t.getLiteral()));
        } else if (n instanceof Code c) {
            sb.append(strip ? styled("inlineCode", c.getLiteral()) : delim("`", "inlineCode", c.getLiteral(), "`"));
        } else if (n instanceof Emphasis) {
            if (strip)
                recur(n, "italic", true, bold, strk, sb);
            else
                sb.append(delim("*", "italic", keepChildren(n, slot), "*"));
        } else if (n instanceof StrongEmphasis) {
            if (strip)
                recur(n, "bold", italic, true, strk, sb);
            else
                sb.append(delim("**", "bold", keepChildren(n, slot), "**"));
        } else if (n instanceof Strikethrough) {
            if (strip)
                recur(n, slot, italic, bold, true, sb);
            else
                sb.append(delim("~~", "strikethrough", keepChildren(n, slot), "~~"));
        } else if (n instanceof Link link) {
            sb.append(strip ? stripLink(link, italic, bold, strk) : keepLink(link, slot));
        } else if (n instanceof Image img) {
            sb.append(strip ? run(altLabel(img), "mdLinkUrl", italic, bold, strk, false) : keepImage(img));
        } else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) {
            sb.append(' ');
        } else if (n instanceof HtmlInline h) {
            sb.append(styled("dim", h.getLiteral()));
        } else {
            recur(n, slot, italic, bold, strk, sb);
        }
    }

    private void recur(Node parent, String slot, boolean italic, boolean bold, boolean strk, StringBuilder sb) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNext())
            node(c, slot, italic, bold, strk, sb);
    }

    private String keepChildren(Node parent, String slot) {
        var sb = new StringBuilder();
        recur(parent, slot, false, false, false, sb);
        return sb.toString();
    }

    // ---- STRIP mode (delimiters consumed) ----

    private String run(String text, String slot, boolean italic, boolean bold, boolean strk, boolean underline) {
        if (text == null || text.isEmpty())
            return "";
        if (!theme.enabled)
            return text;
        var sb = new StringBuilder(theme.style(slot));
        if (bold)
            sb.append(BOLD);
        if (italic)
            sb.append(ITALIC);
        if (underline)
            sb.append(UNDERLINE);
        if (strk)
            sb.append(STRIKE);
        return sb.append(text).append(theme.reset()).toString();
    }

    private String stripLink(Link link, boolean italic, boolean bold, boolean strk) {
        String text = plainText(link);
        String url = link.getDestination();
        if (text.isEmpty())
            text = url == null ? "" : url;
        String rendered = run(text, "mdLink", italic, bold, strk, true);
        if (url == null || url.isBlank() || url.indexOf(' ') >= 0)
            return rendered;
        // Show the URL (dimmed) unless it already IS the text, so it's readable when the terminal ignores OSC 8.
        String visible = text.equals(url)
                                        ? rendered
                                        : rendered + run(" (" + url + ")", "mdLinkUrl", false, false, false, false);
        return osc8(url, visible);
    }

    // ---- KEEP mode (delimiters shown dimmed) — the original barebones look ----

    private String delim(String open, String slot, String inner, String close) {
        return styled("dim", open) + styled(slot, inner) + styled("dim", close);
    }

    private String keepLink(Link link, String slot) {
        String text = keepChildren(link, slot);
        String url = link.getDestination();
        String bracketed = styled("dim", "[") + styled("mdLink", text) + styled("dim", "]");
        if (url == null || url.isBlank() || url.indexOf(' ') >= 0)
            return bracketed;
        return osc8(url, bracketed + styled("dim", "(") + styled("mdLinkUrl", url) + styled("dim", ")"));
    }

    private String keepImage(Image image) {
        String url = image.getDestination() != null ? image.getDestination() : "";
        String visible = styled("dim", "![" + altLabel(image) + "](" + url + ")");
        return url.isBlank() || url.indexOf(' ') >= 0 ? visible : osc8(url, visible);
    }

    String applyTextMarks(String text) {
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
                sb.append(styled("text", text.substring(mark.start(), contentStart)));
                i = contentStart;
                continue;
            }
            sb.append(styled("dim", mark.marker()));
            sb.append(styled(mark.slot(), text.substring(contentStart, end)));
            sb.append(styled("dim", mark.marker()));
            i = end + mark.marker().length();
        }
        return sb.toString();
    }

    /**
     * STRIP-mode counterpart of {@link #applyTextMarks}: consume the ==/++/-- delimiters, style the content by slot.
     */
    private String marksStrip(String text, String slot, boolean italic, boolean bold, boolean strk) {
        var sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            Mark mark = nextMark(text, i);
            if (mark == null) {
                sb.append(run(text.substring(i), slot, italic, bold, strk, false));
                break;
            }
            if (mark.start() > i)
                sb.append(run(text.substring(i, mark.start()), slot, italic, bold, strk, false));
            int contentStart = mark.start() + mark.marker().length();
            int end = text.indexOf(mark.marker(), contentStart);
            if (end < 0) { // no closing marker — the delimiter is literal text
                sb.append(run(text.substring(mark.start(), contentStart), slot, italic, bold, strk, false));
                i = contentStart;
                continue;
            }
            sb.append(run(text.substring(contentStart, end), mark.slot(), italic, bold, strk, false));
            i = end + mark.marker().length();
        }
        return sb.toString();
    }

    record Mark(int start, String marker, String slot) {
    }

    static Mark nextMark(String text, int from) {
        Mark best = null;
        for (var mark : List.of(new Mark(text.indexOf("==", from), "==", "highlight"),
                                        new Mark(text.indexOf("++", from), "++", "diffAdd"),
                                        new Mark(text.indexOf("--", from), "--", "diffDelete"))) {
            if (mark.start() >= 0 && (best == null || mark.start() < best.start()))
                best = mark;
        }
        return best;
    }

    // ---- shared helpers ----

    private static String osc8(String url, String visible) {
        return Util.osc8Link(url, visible);
    }

    private String styled(String slot, String text) {
        return text == null || text.isEmpty() ? "" : theme.styled(slot, text);
    }

    private static String altLabel(Node image) {
        String alt = plainText(image);
        return alt.isEmpty() ? "image" : alt;
    }

    static String plainText(Node node) {
        var sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }

    private static void collectText(Node node, StringBuilder sb) {
        if (node instanceof Text t)
            sb.append(t.getLiteral());
        else if (node instanceof Code c)
            sb.append(c.getLiteral());
        else if (node instanceof SoftLineBreak || node instanceof HardLineBreak)
            sb.append(' ');
        else
            for (Node child = node.getFirstChild(); child != null; child = child.getNext())
                collectText(child, sb);
    }
}
