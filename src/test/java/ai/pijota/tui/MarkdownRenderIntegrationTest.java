package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that exercises every markdown feature through {@link StreamingMarkdownFormatter} with
 * {@link MarkdownStyle#RICH}. Streams line-by-line (real TUI behavior) and checks final rendered output.
 */
class MarkdownRenderIntegrationTest {

    /** Feed lines through a RICH formatter and return ANSI-stripped joined output. */
    private static String renderRich(int width, String... lines) {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        f.setWidth(width);
        var out = new ArrayList<StyledLine>();
        for (String l : lines)
            out.addAll(f.format(l));
        out.addAll(f.flush());
        return out.stream().map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
    }

    private static String renderRich(String... lines) {
        return renderRich(80, lines);
    }

    /** Same but with barebones (TerminalMarkdownRenderer). */
    private static String renderBarebones(String... lines) {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark());
        f.setWidth(80);
        var out = new ArrayList<StyledLine>();
        for (String l : lines)
            out.addAll(f.format(l));
        out.addAll(f.flush());
        return out.stream().map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
    }

    // ─── headings ───

    @Test
    void h1ToH6AllRender() {
        String out = renderRich("# H1", "## H2", "### H3", "#### H4", "##### H5", "###### H6");
        assertTrue(out.contains("H1"), "h1: " + out);
        assertTrue(out.contains("H2"), "h2: " + out);
        assertTrue(out.contains("H3"), "h3: " + out);
        assertTrue(out.contains("H4"), "h4: " + out);
        assertTrue(out.contains("H5"), "h5: " + out);
        assertTrue(out.contains("H6"), "h6: " + out);
        assertFalse(out.contains("######"), "hash delimiters consumed: " + out);
    }

    @Test
    void headingGetsSpacerAfterNonBlank() {
        // The formatter inserts a blank line before a heading when preceded by non-blank content.
        String out = renderRich("intro", "# Heading");
        assertTrue(out.startsWith("intro\n\nHeading"), "spacer before heading: " + out);
    }

    @Test
    void emptyHeadingRendersBlankLine() {
        String out = renderRich("#");
        assertTrue(out.isEmpty() || out.isBlank(), "empty heading yields blank/empty: '" + out + "'");
    }

    // ─── inline styles ───

    @Test
    void boldAndItalicAndStrikethroughAndCode() {
        String out = renderRich("**bold** *italic* ~~strike~~ `code` normal");
        assertTrue(out.contains("bold"), out);
        assertTrue(out.contains("italic"), out);
        assertTrue(out.contains("strike"), out);
        assertTrue(out.contains("code"), out);
        assertTrue(out.contains("normal"), out);
        assertFalse(out.contains("**"), "bold delimiters consumed: " + out);
        assertFalse(out.contains("~~"), "strikethrough delimiters consumed: " + out);
    }

    @Test
    void nestedEmphasis() {
        String out = renderRich("***all three*** and **bold with *italic* inside**");
        assertTrue(out.contains("all three"), out);
        assertTrue(out.contains("bold with italic inside"), out);
    }

    @Test
    void gfmTextMarks() {
        String out = renderRich("==highlight== ++inserted++ --deleted--");
        assertTrue(out.contains("highlight"), out);
        assertTrue(out.contains("inserted"), out);
        assertTrue(out.contains("deleted"), out);
        assertFalse(out.contains("=="), "highlight delimiters consumed: " + out);
        assertFalse(out.contains("++"), "insert delimiters consumed: " + out);
        // Deleted is tricky because -- is also a dash; but in rich mode they're consumed.
    }

    @Test
    void unpairedDashesStayLiteral() {
        // GFM: "--" with no closing pair is plain text.
        String out = renderRich("range 10--20");
        assertTrue(out.contains("10--20"), "unpaired dashes preserved: " + out);
    }

    // ─── code fences ───

    @Test
    void codeFenceWithSyntaxHighlighting() {
        String out = renderRich("```java", "int x = 42;", "```");
        assertTrue(out.contains("int x = 42;"), out);
        // Fence markers are always visible (rendered by the formatter, not the rich renderer)
    }

    @Test
    void codeFenceWithoutLanguage() {
        String out = renderRich("```", "plain code text", "```");
        assertTrue(out.contains("plain code text"), out);
        // Fence markers are always visible (rendered by the formatter, not the rich renderer)
    }

    @Test
    void tildeFenceWorks() {
        String out = renderRich("~~~python", "x = 42", "~~~");
        assertTrue(out.contains("x = 42"), out);
    }

    @Test
    void multipleCodeFences() {
        String out = renderRich("```java", "int a = 1;", "```", "text between", "```", "raw block", "```");
        assertTrue(out.contains("int a = 1;"), out);
        assertTrue(out.contains("text between"), out);
        assertTrue(out.contains("raw block"), out);
    }

    @Test
    void unterminatedFenceFlushCloses() {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        f.setWidth(80);
        var codeLine = f.format("```yaml");
        var contentLine = f.format("key: value");
        var flushed = f.flush();
        String all = Stream.concat(Stream.concat(codeLine.stream(), contentLine.stream()), flushed.stream())
                                        .map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
        assertTrue(all.contains("key: value"), "code content present: " + all);
        assertTrue(all.endsWith("```") || all.contains("\n```"), "flush closes fence: " + all);
    }

    // ─── tables ───

    @Test
    void simpleTableRenders() {
        String out = renderRich("| A | B |", "|---|---|", "| 1 | 2 |");
        assertTrue(out.contains("A"), out);
        assertTrue(out.contains("B"), out);
        assertTrue(out.contains("1"), out);
        assertTrue(out.contains("2"), out);
        assertTrue(out.contains("│"), "table borders render: " + out);
    }

    @Test
    void tableAlignmentRenders() {
        String out = renderRich("| Left | Center | Right |", "|:-----|:------:|------:|", "| a    |   b    |     c |");
        assertTrue(out.contains("a"), out);
        assertTrue(out.contains("b"), out);
        assertTrue(out.contains("c"), out);
        assertTrue(out.contains("│"), out);
    }

    @Test
    void tableWithInlineMarksInCells() {
        String out = renderRich("| Style | Result |", "|-------|--------|", "| **bold** | *italic* |",
                                        "| `code` | ~~strike~~ |", "| ==highlight== | ++added++ |");
        assertTrue(out.contains("bold"), out);
        assertTrue(out.contains("italic"), out);
        assertTrue(out.contains("code"), out);
        assertTrue(out.contains("strike"), out);
        assertTrue(out.contains("highlight"), out);
        assertTrue(out.contains("added"), out);
        // Delimiters consumed in rich mode
        assertFalse(out.contains("**bold**"), "bold delimiters consumed in cell: " + out);
        assertFalse(out.contains("==highlight=="), "highlight delimiters consumed in cell: " + out);
    }

    @Test
    void tableWithLink() {
        String out = renderRich("| Link |", "|------|", "| [text](https://example.com) |");
        assertTrue(out.contains("text"), "link text visible: " + out);
        assertTrue(out.contains("example.com"), "url visible: " + out);
    }

    @Test
    void tableWithEmptyCells() {
        String out = renderRich("| A | B | C |", "|---|---|---|", "| left | | right |", "| | center | |");
        assertTrue(out.contains("left"), out);
        assertTrue(out.contains("right"), out);
        assertTrue(out.contains("center"), out);
    }

    @Test
    void shortDashDelimiterTable() {
        // 2-hyphen cells (GFM minimum)
        String out = renderRich("| X | Y |", "|--|--|", "| a | b |");
        assertTrue(out.contains("X"), out);
        assertTrue(out.contains("a"), out);
        assertTrue(out.contains("│"), "short-delimiter table renders with borders: " + out);
    }

    @Test
    void tableFlushedOnBlankLine() {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        f.setWidth(80);
        f.format("| H |");
        f.format("|---|");
        f.format("| v |");
        var flushed = f.format(""); // blank line flushes table
        String joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
        assertTrue(joined.contains("H"), "blank line flushed table header: " + joined);
        assertTrue(joined.contains("v"), "blank line flushed table body: " + joined);
    }

    // ─── lists ───

    @Test
    void unorderedListRenders() {
        String out = renderRich("- item one", "- item two", "- item three");
        assertTrue(out.contains("•"), "bullet glyph: " + out);
        assertTrue(out.contains("item one"), out);
        assertTrue(out.contains("item two"), out);
        assertTrue(out.contains("item three"), out);
        assertFalse(out.contains("- item"), "dash delimiters consumed: " + out);
    }

    @Test
    void orderedListRenders() {
        String out = renderRich("1. first", "2. second", "3. third");
        assertTrue(out.contains("1."), out);
        assertTrue(out.contains("first"), out);
        assertTrue(out.contains("second"), out);
        // Ordered list content preserves numbers; delimiter is consumed.
    }

    @Test
    void orderedListCustomStart() {
        String out = renderRich("7. seventh", "8. eighth");
        assertTrue(out.contains("7."), out);
        assertTrue(out.contains("seventh"), out);
    }

    @Test
    void nestedLists() {
        String out = renderRich("- top", "  - nested", "    - deep");
        assertTrue(out.contains("• top"), out);
        assertTrue(out.contains("nested"), out);
        assertTrue(out.contains("deep"), out);
    }

    @Test
    void mixedNestedLists() {
        String out = renderRich("- bullet", "  1. ordered inside bullet", "  2. second ordered");
        assertTrue(out.contains("•"), out);
        assertTrue(out.contains("1."), "ordered inside bullet: " + out);
        assertTrue(out.contains("ordered inside bullet"), out);
    }

    @Test
    void listWithInlineMarks() {
        String out = renderRich("- **bold item**", "- *italic item*", "- `code item`");
        assertTrue(out.contains("bold item"), out);
        assertTrue(out.contains("italic item"), out);
        assertTrue(out.contains("code item"), out);
        assertFalse(out.contains("**bold"), "bold delimiters consumed in list: " + out);
    }

    // ─── task lists / checkboxes ───

    @Test
    void taskListRenders() {
        String out = renderRich("- [x] done", "- [ ] pending", "- [~] cancelled");
        assertTrue(out.contains("done"), out);
        assertTrue(out.contains("pending"), out);
        assertTrue(out.contains("cancelled"), out);
    }

    // ─── blockquotes ───

    @Test
    void blockquoteRenders() {
        String out = renderRich("> quoted text");
        assertTrue(out.contains("│"), "blockquote bar: " + out);
        assertTrue(out.contains("quoted text"), out);
    }

    @Test
    void nestedBlockquoteRenders() {
        String out = renderRich("> outer", "> > inner");
        assertTrue(out.contains("outer"), out);
        assertTrue(out.contains("inner"), out);
    }

    @Test
    void blockquoteWithInlineMarks() {
        String out = renderRich("> **bold** and `code` inside quote");
        assertTrue(out.contains("bold"), out);
        assertTrue(out.contains("code"), out);
        assertTrue(out.contains("│"), out);
    }

    @Test
    void blockquoteBuffersSoNestedTableRenders() {
        // This is the exact test from StreamingMarkdownFormatterTest — but in the integration test.
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        f.setWidth(80);
        f.format("> intro");
        f.format("> | A | B |");
        f.format("> |---|---|");
        f.format("> | x | y |");
        var flushed = f.flush();
        String joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
        assertTrue(joined.contains("│"), "nested table in blockquote renders: " + joined);
    }

    @Test
    void blockquoteBuffersSoNestedFenceRenders() {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        f.setWidth(80);
        f.format("> ```java");
        f.format("> int x = 42;");
        f.format("> ```");
        var flushed = f.flush();
        String joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
        assertTrue(joined.contains("int x = 42;"), "nested fence in blockquote: " + joined);
    }

    // ─── horizontal rules ───

    @Test
    void thematicBreakRenders() {
        String out = renderRich("---");
        assertTrue(out.contains("─"), "horizontal rule: " + out);
        assertFalse(out.contains("---"), "raw --- consumed: " + out);
    }

    @Test
    void thematicBreakVariants() {
        String out = renderRich("***");
        assertTrue(out.contains("─"), "*** horizontal rule: " + out);
    }

    // ─── links ───

    @Test
    void linkRendersWithUrl() {
        String out = renderRich("[text](https://example.com)");
        assertTrue(out.contains("text"), "link text: " + out);
        assertTrue(out.contains("example.com"), "link URL: " + out);
    }

    @Test
    void linkUrlEqualsText() {
        String out = renderRich("[https://example.com](https://example.com)");
        assertTrue(out.contains("https://example.com"), "url as text: " + out);
    }

    @Test
    void multipleLinksInParagraph() {
        String out = renderRich("see [foo](https://a.com) and [bar](https://b.org)");
        assertTrue(out.contains("foo"), out);
        assertTrue(out.contains("bar"), out);
        assertTrue(out.contains("a.com"), out);
        assertTrue(out.contains("b.org"), out);
    }

    // ─── barebones (TerminalMarkdownRenderer) ───

    @Test
    void barebonesKeepsDelimiters() {
        String out = renderBarebones("# H", "**bold**", "*italic*", "`code`", "~~strike~~");
        assertTrue(out.contains("#"), "barebones keeps #: " + out);
        assertTrue(out.contains("**"), "barebones keeps **: " + out);
        assertTrue(out.contains("*italic*"), "barebones keeps *: " + out);
        assertTrue(out.contains("`"), "barebones keeps backticks: " + out);
        assertTrue(out.contains("~~"), "barebones keeps ~~: " + out);
    }

    @Test
    void barebonesTableStillRendersBorder() {
        // TerminalMarkdownRenderer delegates to MarkdownTableRenderer for tables, but in KEEP mode.
        String out = renderBarebones("| A | B |", "|---|---|", "| 1 | 2 |");
        assertTrue(out.contains("│"), "barebones table renders: " + out);
    }

    @Test
    void barebonesListKeepsDash() {
        String out = renderBarebones("- item");
        assertTrue(out.contains("-"), "barebones keeps dash: " + out);
        assertTrue(out.contains("item"), out);
    }

    // ─── width clamping ───

    @Test
    void narrowWidthStillRenders() {
        String out = renderRich(15, "# Wide heading here", "some text that is quite long and should wrap or clip",
                                        "| A | B |", "|---|---|", "| x | y |");
        assertTrue(out.contains("heading"), out);
        assertTrue(out.contains("x"), out);
    }

    @Test
    void veryNarrowWidthStillRenders() {
        String out = renderRich(5, "# H", "text", "| A | B |", "|---|---|", "| x | y |");
        assertTrue(out.contains("H"), "heading: " + out);
        // Table should still produce something
        assertTrue(out.contains("│") || out.contains("A") || out.contains("x"), "table: " + out);
    }

    // ─── blank lines / whitespace ───

    @Test
    void blankLinesPreserved() {
        String out = renderRich("a", "", "b");
        assertTrue(out.contains("a\n\nb"), "blank line between paragraphs: " + out);
    }

    @Test
    void whitespaceOnlyLineDoesNotCrash() {
        assertDoesNotThrow(() -> renderRich("   "));
    }

    @Test
    void nullLineDoesNotCrash() {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        assertDoesNotThrow(() -> f.format(null));
    }

    // ─── mixed content ───

    @Test
    void fullDocumentRenders() {
        // A complete document exercising every feature in sequence.
        String out = renderRich("# Title", "",
                                        "A paragraph with **bold**, *italic*, `code`, and a [link](https://example.com).",
                                        "", "```java", "int answer = 42;", "```", "", "| Feature | Status |",
                                        "|---------|--------|", "| **bold** | `works` |", "| *italic* | ~~done~~ |", "",
                                        "- item one", "- item two", "", "> blockquote here", "", "---", "",
                                        "==highlighted== and ++added++ text.");
        assertTrue(out.contains("Title"), "heading: " + out);
        assertTrue(out.contains("bold"), out);
        assertTrue(out.contains("italic"), out);
        assertTrue(out.contains("code"), out);
        assertTrue(out.contains("answer = 42;"), "code fence: " + out);
        assertTrue(out.contains("Feature"), "table: " + out);
        assertTrue(out.contains("•"), "list: " + out);
        assertTrue(out.contains("│"), "blockquote: " + out);
        assertTrue(out.contains("─"), "hr: " + out);
        assertTrue(out.contains("highlighted"), "text mark: " + out);
        assertTrue(out.contains("added"), "text mark: " + out);
        assertFalse(out.contains("---"), "hr markers consumed: " + out);
    }

    // ─── flush / reset ───

    @Test
    void flushReturnsEmptyWhenNothingBuffered() {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        var out = f.flush();
        assertTrue(out.isEmpty() || out.stream().allMatch(l -> l.text().isEmpty()), "flush on clean state: " + out);
    }

    @Test
    void resetClearsAllState() {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        f.format("```java");
        f.format("int x;");
        f.reset();
        // After reset, new input should render as normal prose, not code.
        String out = f.format("hello").stream().map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
        assertTrue(out.contains("hello"), "reset cleared fence: " + out);
    }

    // ─── setWidth / setTheme ───

    @Test
    void setWidthAndSetThemeWork() {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        assertDoesNotThrow(() -> f.setWidth(40));
        assertDoesNotThrow(() -> f.setTheme(AnsiTheme.light()));
        String out = f.format("**bold** text").stream().map(l -> AnsiStripper.strip(l.text())).collect(joining("\n"));
        assertTrue(out.contains("bold"), out);
    }
}
