package org.pijota.tui;

import org.pijota.modes.SyntaxHighlighter;
import org.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class StreamingMarkdownFormatter {
    // GFM requires only one-or-more hyphens per delimiter cell (with optional alignment colons); a stricter -{3,}
    // wrongly rejects valid tables whose separators use 1–2 dashes (e.g. "|--|--|"), dropping them to raw markdown.
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-+:?");

    private final MarkdownRenderer renderer;
    private AnsiTheme theme;
    private final String defaultTextSlot;
    private final boolean defaultItalic;
    private final LineBuffer table = new LineBuffer();
    private final LineBuffer quote = new LineBuffer();
    private String pendingTableHeader;
    private boolean inFence;
    private SyntaxHighlighter fenceHighlighter;
    /**
     * What the last emitted line was. Three states, not two booleans: a leading blank must survive (it separates a
     * message from the thinking block above it) while a leading heading must not gain one, so "nothing yet" is
     * genuinely distinct from "a blank" -- and a pair of booleans could also reach a fourth, meaningless combination.
     */
    private enum Last {
        NOTHING, BLANK, CONTENT
    }

    private Last last = Last.NOTHING;

    public StreamingMarkdownFormatter(AnsiTheme theme) {
        this(theme, "text", false, MarkdownStyle.PLAIN);
    }

    public StreamingMarkdownFormatter(AnsiTheme theme, MarkdownStyle style) {
        this(theme, "text", false, style);
    }

    public StreamingMarkdownFormatter(AnsiTheme theme, String defaultTextSlot, boolean defaultItalic) {
        this(theme, defaultTextSlot, defaultItalic, MarkdownStyle.PLAIN);
    }

    public StreamingMarkdownFormatter(AnsiTheme theme, String defaultTextSlot, boolean defaultItalic,
                                    MarkdownStyle style) {
        this.theme = theme;
        this.defaultTextSlot = defaultTextSlot;
        this.defaultItalic = defaultItalic;
        this.renderer = style == MarkdownStyle.RICH
                                        ? new RichMarkdownRenderer(theme)
                                        : new TerminalMarkdownRenderer(theme);
    }

    public void setWidth(int width) {
        renderer.setWidth(width);
    }

    public void setTheme(AnsiTheme theme) {
        this.theme = theme;
        renderer.setTheme(theme);
    }

    public List<StyledLine> format(String raw) {
        String line = raw == null ? "" : raw;
        String stripped = line.stripLeading();
        String marker = fenceMarker(stripped);
        if (marker != null)
            return toggleFence(stripped, marker);
        if (inFence) {
            var rendered = List.of(StyledLine.of(renderCodeLine(line)));
            last = Last.CONTENT;
            return rendered;
        }
        return renderChunks(acceptMarkdownLine(line));
    }

    public List<StyledLine> flush() {
        var out = renderChunks(flushBufferedMarkdown());
        if (inFence) { // a stream that ended mid-block still closes cleanly
            leaveFence();
            addBlank(out);
        }
        return out;
    }

    public void reset() {
        table.clear();
        quote.clear();
        pendingTableHeader = null;
        leaveFence();
        last = Last.NOTHING;
    }

    /**
     * A fence opens or closes. The markers themselves are never shown: everything else in this view is rendered rather
     * than quoted (a table shows borders, not pipes; a heading shows weight, not hashes), and a stray {@code ```} is
     * the one place the source format used to leak through. The block is set off by blank lines and introduced by its
     * language instead, so the code lines stay bare and can be selected and copied without picking up a rail.
     */
    private List<StyledLine> toggleFence(String stripped, String marker) {
        var out = new ArrayList<>(renderChunks(flushBufferedMarkdown()));
        if (inFence) {
            leaveFence();
            addBlank(out);
            return out;
        }
        String language = fenceLanguage(enterFence(stripped, marker));
        addBlank(out); // never let a code block butt against the paragraph above it
        if (!language.isBlank()) {
            out.add(fenceLine(language));
            last = Last.CONTENT;
        }
        return out;
    }

    private String enterFence(String stripped, String marker) {
        String info = stripped.substring(marker.length()).strip();
        inFence = true;
        String language = fenceLanguage(info);
        fenceHighlighter = language.isEmpty() ? null : new SyntaxHighlighter(language, theme);
        return info;
    }

    private void leaveFence() {
        inFence = false;
        fenceHighlighter = null;
    }

    private String renderCodeLine(String line) {
        if (fenceHighlighter != null)
            return fenceHighlighter.highlightLine(line);
        return styled("codeBody", line);
    }

    private List<String> acceptMarkdownLine(String line) {
        var out = new ArrayList<String>();
        if (line == null)
            line = "";

        if (line.isBlank()) {
            quote.flushInto(out);
            table.flushInto(out);
            flushPendingHeaderInto(out);
            out.add("");
            return out;
        }

        // Blockquotes buffer their whole run (like tables) so a table or code block nested inside parses with full
        // context. Every line of a quote starts with '>' (after optional indent), so the run is self-delimiting.
        if (line.stripLeading().startsWith(">")) {
            table.flushInto(out);
            flushPendingHeaderInto(out);
            quote.add(line);
            return out;
        }
        if (quote.active())
            quote.flushInto(out); // a non-quote line ends the quote; fall through to normal handling

        String stripped = line.strip();
        if (pendingTableHeader != null) {
            if (isTableSeparator(stripped)) {
                table.add(pendingTableHeader);
                pendingTableHeader = null;
                table.add(line);
                return out;
            }
            flushPendingHeaderInto(out);
        }

        if (table.active()) {
            if (line.indexOf('|') >= 0) {
                table.add(line);
            } else {
                table.flushInto(out);
                out.add(line);
            }
            return out;
        }

        if (line.indexOf('|') >= 0)
            pendingTableHeader = line;
        else
            out.add(line);
        return out;
    }

    private List<String> flushBufferedMarkdown() {
        var out = new ArrayList<String>();
        quote.flushInto(out);
        table.flushInto(out);
        flushPendingHeaderInto(out);
        return out;
    }

    private void flushPendingHeaderInto(List<String> out) {
        if (pendingTableHeader == null)
            return;
        out.add(pendingTableHeader);
        pendingTableHeader = null;
    }

    private List<StyledLine> renderChunks(List<String> chunks) {
        var out = new ArrayList<StyledLine>();
        for (var chunk : chunks) {
            if (chunk.isEmpty()) {
                addBlank(out);
                continue;
            }
            if (last == Last.CONTENT && startsWithHeading(chunk)) {
                out.add(StyledLine.of(""));
                last = Last.BLANK;
            }
            var rendered = renderer.render(chunk, defaultTextSlot, defaultItalic);
            out.addAll(rendered);
            if (rendered.stream().anyMatch(line -> !line.text().isEmpty()))
                last = Last.CONTENT; // a chunk that rendered to nothing leaves the last emitted line unchanged
        }
        return out;
    }

    private static String fenceMarker(String stripped) {
        if (stripped.startsWith("```"))
            return "```";
        if (stripped.startsWith("~~~"))
            return "~~~";
        return null;
    }

    static String fenceLanguage(String info) {
        if (info == null || info.isBlank())
            return "";
        String lang = info.strip().split("\\s+", 2)[0].toLowerCase();
        if (lang.startsWith("language-"))
            lang = lang.substring("language-".length());
        return switch (lang) {
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            case "rb" -> "ruby";
            case "yml" -> "yaml";
            case "c++" -> "cpp";
            case "c#" -> "csharp";
            default -> lang;
        };
    }

    /**
     * One blank line is a paragraph break; a run of them is the same break with extra vertical noise, and models emit
     * runs freely. Only consecutive blanks collapse: a leading blank is kept, because it is what separates a message
     * from the thinking block or tool result above it. Blanks inside a fence never reach this path.
     */
    private void addBlank(List<StyledLine> out) {
        if (last != Last.BLANK) {
            out.add(StyledLine.of(""));
            last = Last.BLANK;
        }
    }

    /** The language label above a code block; the "codeFence" slot, dim so it introduces without competing. */
    private StyledLine fenceLine(String text) {
        return StyledLine.of(styled("codeFence", text));
    }

    private String styled(String slot, String text) {
        return text == null || text.isEmpty() ? "" : theme.styled(slot, text);
    }

    private static boolean isTableSeparator(String stripped) {
        if (stripped.indexOf('|') < 0)
            return false;
        String s = stripped;
        if (s.startsWith("|"))
            s = s.substring(1);
        if (s.endsWith("|"))
            s = s.substring(0, s.length() - 1);
        for (String cell : s.split("\\|", -1)) {
            if (!TABLE_SEPARATOR_CELL.matcher(cell.strip()).matches())
                return false;
        }
        return true;
    }

    private static boolean startsWithHeading(String markdown) {
        if (markdown == null || markdown.isEmpty())
            return false;
        int end = markdown.indexOf('\n');
        String firstLine = end >= 0 ? markdown.substring(0, end) : markdown;
        int i = 0;
        while (i < firstLine.length() && firstLine.charAt(i) == ' ')
            i++;
        if (i >= firstLine.length() || firstLine.charAt(i) != '#')
            return false;
        int n = 0;
        while (i + n < firstLine.length() && firstLine.charAt(i + n) == '#')
            n++;
        return n < firstLine.length() && firstLine.charAt(i + n) == ' ';
    }
}
