package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StreamingMarkdownFormatterTest {

    private static String richJoined(String... lines) {
        var f = new StreamingMarkdownFormatter(AnsiTheme.dark(), MarkdownStyle.RICH);
        var out = new ArrayList<StyledLine>();
        for (String l : lines)
            out.addAll(f.format(l));
        out.addAll(f.flush());
        return out.stream().map(l -> AnsiStripper.strip(l.text())).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void blockquoteBuffersSoNestedTableRenders() {
        // Each line streams separately; the quote buffers until flush, so CommonMark sees the whole quote and the
        // nested table parses instead of showing as raw pipes.
        String out = richJoined("> intro", "> | A | B |", "> |---|---|", "> | x | y |");
        assertTrue(out.contains("│"), "nested table renders (bar + column separators): " + out);
        assertFalse(out.contains("| A | B |"), "raw table pipes must not leak: " + out);
    }

    @Test
    void blockquoteBuffersSoNestedFencedCodeRenders() {
        String out = richJoined("> ```java", "> int x = 42;", "> ```");
        assertTrue(out.contains("int x = 42;"), "code content present: " + out);
        assertFalse(out.contains("```"), "fence markers consumed: " + out);
    }

    @Test
    void thinkingRendersSameMarkdownStructureAsContentJustDimmed() {
        var theme = AnsiTheme.dark();
        var content = new StreamingMarkdownFormatter(theme);
        var thinking = new StreamingMarkdownFormatter(theme.thinking());
        content.setWidth(60);
        thinking.setWidth(60);

        var lines = List.of("# Heading", "A **bold** word, an *italic* one, and `inline code`.", "```java",
                                        "int answer = 42;", "```", "| Col A | Col B |", "|-------|-------|",
                                        "| 1     | 2     |", "");
        var c = new StringBuilder();
        var t = new StringBuilder();
        for (String l : lines) {
            for (var sl : content.format(l))
                c.append(sl.text()).append('\n');
            for (var sl : thinking.format(l))
                t.append(sl.text()).append('\n');
        }
        for (var sl : content.flush())
            c.append(sl.text()).append('\n');
        for (var sl : thinking.flush())
            t.append(sl.text()).append('\n');

        String cRaw = c.toString();
        String tRaw = t.toString();
        // Same markdown pipeline → identical structure (heading, bold/italic, syntax-highlighted code, table) once
        // ANSI is stripped: thinking is rendered "the same way" as content.
        assertEquals(AnsiStripper.strip(cRaw), AnsiStripper.strip(tRaw),
                                        "thinking must render the same markdown structure as content");
        // ...but dimmed: the styled bytes differ while thinking still carries markdown/syntax styling.
        assertNotEquals(cRaw, tRaw, "thinking output must be styled differently (dimmed)");
        assertTrue(tRaw.contains("["), "thinking output must still be markdown/syntax styled");
        String stripped = AnsiStripper.strip(tRaw);
        assertTrue(stripped.contains("Heading"), stripped);
        assertTrue(stripped.contains("│"), "table must render with borders: " + stripped);
    }

    @Test
    void introducesACodeBlockByLanguageRatherThanShowingItsFenceMarkers() {
        // Everything else in this view is rendered rather than quoted: tables show borders, not pipes; headings show
        // weight, not hashes. A literal ``` was the one place the source format leaked through, and it rendered
        // differently depending on nesting (the rich renderer already consumed it).
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());

        var opening = formatter.format("```python");
        var code = formatter.format("@lru_cache(maxsize=None)").getFirst().text();
        var closing = formatter.format("```");

        String openText = opening.stream().map(l -> AnsiStripper.strip(l.text())).collect(Collectors.joining("\n"));
        assertFalse(openText.contains("```"), "no fence marker: " + openText);
        assertTrue(openText.contains("python"), "the language introduces the block: " + openText);
        assertEquals("@lru_cache(maxsize=None)", AnsiStripper.strip(code));
        assertTrue(code.contains("\u001B["), "code should be syntax styled");
        for (var line : closing)
            assertFalse(AnsiStripper.strip(line.text()).contains("```"), "no closing marker: " + line.text());
        assertTrue(opening.stream().anyMatch(l -> l.text().contains("\u001B[")), "the label is theme styled");
    }

    @Test
    void anUnlabelledCodeBlockAddsNoLabelLine() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("Some text.");
        var opening = formatter.format("```");
        var stripped = opening.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(stripped.stream().allMatch(String::isBlank), "only separation, nothing to label: " + stripped);
    }

    @Test
    void flushClosesUnterminatedFence() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());

        formatter.format("```java");
        formatter.format("class Test {}");

        var flushed = formatter.flush();

        // The point is that the block is closed, not that a marker is printed: the next line must render as markdown
        // again rather than as more code.
        assertTrue(AnsiStripper.strip(flushed.getLast().text()).isBlank(), "block is closed off: " + flushed);
        assertTrue(AnsiStripper.strip(formatter.format("# After").getFirst().text()).contains("After"),
                                        "the formatter left fence mode");
    }

    @Test
    void formatNullReturnsEmptyLine() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        var lines = formatter.format(null);
        assertEquals(1, lines.size());
        assertEquals("", lines.get(0).text());
    }

    @Test
    void aRunOfBlankLinesCollapsesToOneButTheFirstIsKept() {
        // A leading blank sets the message off from the thinking block or tool result above it, so it must survive;
        // the extra ones models emit freely are just vertical noise.
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        assertEquals(1, formatter.format("").size(), "the separating blank is kept");
        assertEquals(0, formatter.format("").size(), "a second blank adds nothing");
        assertEquals(0, formatter.format("   ").size(), "whitespace-only counts as blank too");
        assertEquals("text", AnsiStripper.strip(formatter.format("text").getFirst().text()));
        assertEquals(1, formatter.format("").size(), "after content, a blank breaks the paragraph again");
    }

    @Test
    void aCodeBlockIsFollowedByOneBlankLineNotTwo() {
        // The block closes with a blank of its own; the model's own trailing blank used to add a second.
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("```python");
        formatter.format("print(1)");
        int closing = formatter.format("```").size();
        int modelBlank = formatter.format("").size();
        assertEquals(1, closing + modelBlank, "exactly one blank separates the block from what follows");
    }

    @Test
    void resetClearsFenceAndTableState() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("```java");
        formatter.format("class Test {}");
        formatter.reset();

        var plain = formatter.format("hello");
        assertTrue(AnsiStripper.strip(plain.get(0).text()).contains("hello"));
    }

    @Test
    void setWidthAndThemeDoNotThrow() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.setWidth(40);
        formatter.setTheme(AnsiTheme.light());
        assertFalse(formatter.format("text").isEmpty());
    }

    @Test
    void blankLineFlushesPendingTableHeader() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        var flushed = formatter.format("");
        assertFalse(flushed.isEmpty());
        assertTrue(AnsiStripper.strip(flushed.get(0).text()).contains("| a | b |"));
    }

    @Test
    void tableBufferedUntilSeparatorThenRendered() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        formatter.format("|---|---|");
        formatter.format("| 1 | 2 |");
        var flushed = formatter.flush();

        var joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("a") && s.contains("b")));
        assertTrue(joined.stream().anyMatch(s -> s.contains("1") && s.contains("2")));
    }

    @Test
    void tableWithShortDashDelimiterStillRenders() {
        // GFM allows 1–2 dashes per delimiter cell; a stricter -{3,} dropped such tables to raw markdown.
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| | a | b |");
        formatter.format("|--|--|--|");
        formatter.format("| x | 1 | 2 |");
        var flushed = formatter.flush();

        var joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("│")), "table must render with borders: " + joined);
        assertFalse(joined.stream().anyMatch(s -> s.contains("|--|")), "raw delimiter must not leak: " + joined);
    }

    @Test
    void tableFlushedOnBlankLine() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        formatter.format("|---|---|");
        formatter.format("| 1 | 2 |");
        var flushed = formatter.format("");

        var joined = flushed.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("1")));
    }

    @Test
    void headingAfterNonBlankLineGetsSpacer() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("intro");
        var result = formatter.format("# heading");
        assertTrue(result.size() >= 2);
        assertEquals("", AnsiStripper.strip(result.get(0).text()));
        assertTrue(AnsiStripper.strip(result.get(1).text()).contains("heading"));
    }

    @Test
    void tildeFenceMarkersWork() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        var opening = formatter.format("~~~ruby");
        String label = opening.stream().map(l -> AnsiStripper.strip(l.text())).collect(Collectors.joining());
        assertEquals("ruby", label, "a ~~~ fence is a fence, and is labelled the same way");
    }

    @Test
    void defaultItalicAndTextSlotAreRespected() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark(), "text", true);
        var lines = formatter.format("plain");
        assertFalse(lines.isEmpty());
        assertEquals("plain", AnsiStripper.strip(lines.get(0).text()));
    }

    @Test
    void codeWithoutHighlighterUsesCodeBodySlot() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("```foo");
        var code = formatter.format("plain code").getFirst().text();
        formatter.format("```");
        assertEquals("plain code", AnsiStripper.strip(code));
        assertTrue(code.contains("\u001B["));
    }

    @Test
    void pendingTableHeaderFlushedWhenNextLineIsNotSeparator() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        var result = formatter.format("not a separator");
        var joined = result.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("| a | b |")));
        assertTrue(joined.stream().anyMatch(s -> s.contains("not a separator")));
    }

    @Test
    void tableFlushedWhenLineLeavesTable() {
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        formatter.format("|---|---|");
        formatter.format("| 1 | 2 |");
        var result = formatter.format("plain");
        var joined = result.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("1") && s.contains("2")));
        assertTrue(joined.stream().anyMatch(s -> s.contains("plain")));
    }

    @Test
    void fenceLanguageBlankAndPrefix() {
        var noLangFormatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        var noLang = noLangFormatter.format("```").stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(noLang.stream().allMatch(String::isBlank), "nothing to label: " + noLang);

        var prefixedFormatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        String prefixed = prefixedFormatter.format("```language-js").stream().map(l -> AnsiStripper.strip(l.text()))
                                        .collect(Collectors.joining());
        assertEquals("javascript", prefixed, "the label is the normalized language, not the raw info string");
    }

    @Test
    void invalidTableSeparatorDoesNotFormTable() {
        // A delimiter cell needs at least one hyphen (GFM); colons alone are not a separator, so no table forms.
        var formatter = new StreamingMarkdownFormatter(AnsiTheme.dark());
        formatter.format("| a | b |");
        var result = formatter.format("| : | : |");
        var joined = result.stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(joined.stream().anyMatch(s -> s.contains("| a | b |")));
    }
}
