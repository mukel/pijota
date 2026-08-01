package org.pijota.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.commonmark.ext.gfm.tables.TableCell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

class MarkdownTableRendererTest {

    private static final AnsiTheme THEME = AnsiTheme.dark();

    private static MarkdownTableRenderer renderer(int width) {
        return new MarkdownTableRenderer(THEME, width);
    }

    private static List<String> renderTable(String md, int width) {
        var result = MarkdownTableRenderer.renderIfTable(md, THEME, width);
        assertTrue(result.isPresent(), "should parse as table: " + md);
        return result.get().stream().map(l -> AnsiStripper.strip(l.text())).toList();
    }

    @Test
    void fitWidthsOnZeroColumnsDoesNotSpin() {
        // A degenerate table with no columns must not hang the grow loop (sum stays 0 < available forever).
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                                        () -> MarkdownTableRenderer.fitWidths(new int[0], 80));
    }

    // ─── couldBeTable ───

    @Test
    void couldBeTableDetectsPipesAndNewlines() {
        assertTrue(MarkdownTableRenderer.couldBeTable("| a | b |\n| c | d |"));
        assertTrue(MarkdownTableRenderer.couldBeTable("|\n|"));
    }

    @Test
    void couldBeTableRejectsNoPipes() {
        assertFalse(MarkdownTableRenderer.couldBeTable("no pipes here\nsecond line"));
    }

    @Test
    void couldBeTableRejectsNoNewlines() {
        assertFalse(MarkdownTableRenderer.couldBeTable("| single line |"));
    }

    @Test
    void couldBeTableRejectsEmpty() {
        assertFalse(MarkdownTableRenderer.couldBeTable(""));
    }

    @Test
    void couldBeTableRejectsNull() {
        assertFalse(MarkdownTableRenderer.couldBeTable(null));
    }

    // ─── renderIfTable ───

    @Test
    void rendersValidGfmTable() {
        var clean = renderTable("| Col1 | Col2 |\n|---|---|\n|a|b|\n|c|d|", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("Col1")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("Col2")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("a") && l.contains("b")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("c") && l.contains("d")));
    }

    @Test
    void shortContentTablesStayCompactNotFullWidth() {
        // tiny cells must not be stretched to fill the terminal (columns are content-sized, GitHub-style)
        var clean = renderTable("| a | b |\n|---|---|\n| 1 | 2 |", 80);
        int maxWidth = clean.stream().mapToInt(String::length).max().orElse(0);
        assertTrue(maxWidth < 20, "short table should stay compact, was " + maxWidth + " wide: " + clean);
    }

    @Test
    void wideContentTablesStillShrinkToFitTheTerminal() {
        var narrow = renderTable("| " + "x".repeat(60) + " | " + "y".repeat(60) + " |\n|---|---|\n| a | b |", 40);
        assertTrue(narrow.stream().allMatch(l -> l.length() <= 40), "columns must shrink to fit width 40: " + narrow);
    }

    @Test
    void returnsEmptyForNonTableMarkdown() {
        assertTrue(MarkdownTableRenderer.renderIfTable("not a table", THEME, 80).isEmpty());
    }

    @Test
    void returnsEmptyForTextWithSinglePipeLine() {
        assertTrue(MarkdownTableRenderer.renderIfTable("| single line", THEME, 80).isEmpty());
    }

    @Test
    void returnsEmptyForTableFollowedByOtherMarkdown() {
        var md = "| H |\n|---|\n| v |\n\nSome paragraph text.";
        assertTrue(MarkdownTableRenderer.renderIfTable(md, THEME, 80).isEmpty());
    }

    @Test
    void returnsEmptyForParagraphWithPipesAndNewlines() {
        var md = "Here is | some text\nwith | pipes";
        assertTrue(MarkdownTableRenderer.renderIfTable(md, THEME, 80).isEmpty());
    }

    @Test
    void separatorRowIsPresentBetweenHeaderAndBody() {
        var result = MarkdownTableRenderer.renderIfTable("| H |\n|---|\n| v |", THEME, 80);
        assertTrue(result.isPresent());
        assertTrue(result.get().size() >= 4, "should contain blank lines and separator row");
    }

    @Test
    void noSeparatorRowForHeaderOnlyTable() {
        var result = MarkdownTableRenderer.renderIfTable("| H1 | H2 |\n|---|---|", THEME, 80);
        assertTrue(result.isPresent());
        var clean = result.get().stream().map(l -> AnsiStripper.strip(l.text())).toList();
        assertTrue(clean.stream().anyMatch(l -> l.contains("H1")));
        assertFalse(clean.stream().anyMatch(l -> l.contains("┼")), "no separator for header-only table");
    }

    @Test
    void tableWithAlignmentRight() {
        var clean = renderTable("| Name  | Value |\n|:------|------:|\n| pi    | 3.14 |", 80);
        var dataRow = clean.stream().filter(l -> l.contains("pi")).findFirst().orElseThrow();
        var parts = dataRow.split("│");
        assertTrue(parts.length >= 2, "should have at least 2 columns: " + dataRow);
        var valueCol = parts[1].strip();
        assertEquals("3.14", valueCol, "right-aligned column should have value right-justified");
    }

    @Test
    void tableWithAlignmentCenter() {
        var clean = renderTable("| H |\n|:---:|\n| v |", 80);
        var dataRow = clean.stream().filter(l -> l.contains("v")).findFirst().orElseThrow();
        assertTrue(dataRow.startsWith(" "), "center-aligned value should have left padding: '" + dataRow + "'");
        assertTrue(dataRow.endsWith(" "), "center-aligned value should have right padding: '" + dataRow + "'");
    }

    @Test
    void tableWithAlignmentLeft() {
        var clean = renderTable("| H |\n|:---|\n| val |", 80);
        var dataRow = clean.stream().filter(l -> l.contains("val")).findFirst().orElseThrow();
        var stripped = dataRow.strip();
        assertTrue(stripped.startsWith("v"), "left-aligned value should start at left: '" + stripped + "'");
    }

    @Test
    void raggedRowsPadsMissingColumnsWithEmpty() {
        var clean = renderTable("| A | B | C |\n|---|---|---|\n| x | y |", 80);
        var dataRow = clean.stream().filter(l -> l.contains("x")).findFirst().orElseThrow();
        assertTrue(dataRow.contains("x"), "should contain x");
        assertTrue(dataRow.contains("y"), "should contain y");
        assertTrue(dataRow.split("│").length >= 3, "should have 3 columns even with ragged row: " + dataRow);
    }

    @Test
    void emptyCellsRenderAsBlank() {
        var clean = renderTable("| A | B |\n|---|---|\n| | |", 80);
        var dataRow = clean.stream().filter(l -> l.trim().startsWith("│") || l.trim().isEmpty()).toList();
        assertFalse(dataRow.isEmpty());
    }

    @Test
    void singleColumnTable() {
        var clean = renderTable("| H |\n|---|\n| v |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("H")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("v")));
    }

    @Test
    void tableWithInlineCode() {
        var clean = renderTable("| Code |\n|---|\n| `hello` |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("hello")));
    }

    @Test
    void tableWithBoldText() {
        var clean = renderTable("| Bold |\n|---|\n| **bold** |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("bold")));
    }

    @Test
    void tableWithItalicText() {
        var clean = renderTable("| Italic |\n|---|\n| *ital* |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("ital")));
    }

    @Test
    void tableWithStrikethrough() {
        var clean = renderTable("| Strike |\n|---|\n| ~~done~~ |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("done")));
    }

    @Test
    void tableWithLink() {
        var clean = renderTable("| Link |\n|---|\n| [text](https://example.com) |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("text")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("example.com")));
    }

    @Test
    void tableWithTextMarksHighlight() {
        var clean = renderTable("| Text |\n|---|\n| ==highlighted== |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("highlighted")));
    }

    @Test
    void tableWithTextMarksDiffAdd() {
        var clean = renderTable("| Text |\n|---|\n| ++added++ |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("added")));
    }

    @Test
    void tableWithTextMarksDiffDelete() {
        var clean = renderTable("| Text |\n|---|\n| --deleted-- |", 80);
        assertTrue(clean.stream().anyMatch(l -> l.contains("deleted")));
    }

    @Test
    void longTextWrapsWithinCell() {
        var clean = renderTable("| Short | Long |\n|---|---|\n| x | this is a very long cell that should wrap |", 30);
        var linesWithLong = clean.stream().filter(l -> l.contains("this") || l.contains("long") || l.contains("wrap"))
                                        .toList();
        assertFalse(linesWithLong.isEmpty());
        assertTrue(linesWithLong.size() > 1, "long cell text should wrap to multiple lines");
    }

    @Test
    void narrowWidthStillRendersTable() {
        var clean = renderTable("| A | B |\n|---|---|\n| 1 | 2 |", 15);
        assertFalse(clean.isEmpty());
        assertTrue(clean.stream().anyMatch(l -> l.contains("A")));
        assertTrue(clean.stream().anyMatch(l -> l.contains("1")));
    }

    // ─── sum ───

    @Test
    void sumAddsAllElements() {
        assertEquals(0, MarkdownTableRenderer.sum(new int[0]));
        assertEquals(10, MarkdownTableRenderer.sum(new int[] {4, 6}));
        assertEquals(15, MarkdownTableRenderer.sum(new int[] {1, 2, 3, 4, 5}));
    }

    @Test
    void sumOfNegativeValues() {
        assertEquals(-6, MarkdownTableRenderer.sum(new int[] {-1, -2, -3}));
    }

    // ─── widest ───

    @Test
    void widestReturnsIndexOfLargest() {
        assertEquals(0, MarkdownTableRenderer.widest(new int[] {5}));
        assertEquals(2, MarkdownTableRenderer.widest(new int[] {1, 3, 7, 2}));
        assertEquals(0, MarkdownTableRenderer.widest(new int[] {5, 5}));
    }

    @Test
    void widestOfEmptyArray() {
        assertEquals(0, MarkdownTableRenderer.widest(new int[0]));
    }

    @Test
    void widestOfSingleElement() {
        assertEquals(0, MarkdownTableRenderer.widest(new int[] {42}));
    }

    @Test
    void widestTieReturnsFirstIndex() {
        assertEquals(0, MarkdownTableRenderer.widest(new int[] {7, 7, 7}));
    }

    // ─── spaces ───

    @Test
    void spacesGeneratesRepeatedSpaces() {
        assertEquals("", MarkdownTableRenderer.spaces(0));
        assertEquals("   ", MarkdownTableRenderer.spaces(3));
        assertEquals(" ".repeat(10), MarkdownTableRenderer.spaces(10));
    }

    @Test
    void spacesNegativeReturnsEmpty() {
        assertEquals("", MarkdownTableRenderer.spaces(-5));
    }

    // ─── align ───

    @Test
    void alignLeftPadsRight() {
        assertEquals("ab    ", AnsiStripper.strip(MarkdownTableRenderer.align("ab", 6, TableCell.Alignment.LEFT)));
    }

    @Test
    void alignRightPadsLeft() {
        assertEquals("    ab", AnsiStripper.strip(MarkdownTableRenderer.align("ab", 6, TableCell.Alignment.RIGHT)));
    }

    @Test
    void alignCenterPadsBoth() {
        var stripped = AnsiStripper.strip(MarkdownTableRenderer.align("ab", 6, TableCell.Alignment.CENTER));
        assertTrue(stripped.startsWith(" "));
        assertTrue(stripped.endsWith(" "));
        assertEquals(6, stripped.length());
    }

    @Test
    void alignCenterOddPad() {
        var stripped = AnsiStripper.strip(MarkdownTableRenderer.align("ab", 7, TableCell.Alignment.CENTER));
        assertEquals(7, stripped.length());
        int left = 0;
        while (left < stripped.length() && stripped.charAt(left) == ' ')
            left++;
        int right = stripped.length() - 1;
        while (right >= 0 && stripped.charAt(right) == ' ')
            right--;
        int leftPad = left;
        int rightPad = stripped.length() - 1 - right;
        assertEquals(2, leftPad, "odd pad: left = pad/2 = 2");
        assertEquals(3, rightPad, "odd pad: right = pad - left = 3");
    }

    @Test
    void alignReturnsTextWhenNoPadNeeded() {
        assertEquals("abcdef", AnsiStripper.strip(MarkdownTableRenderer.align("abcdef", 6, TableCell.Alignment.LEFT)));
    }

    @Test
    void alignReturnsTextWhenTextExceedsWidth() {
        assertEquals("abcdef", AnsiStripper.strip(MarkdownTableRenderer.align("abcdef", 4, TableCell.Alignment.LEFT)));
    }

    @Test
    void alignNullAlignmentDefaultsToLeft() {
        assertEquals("ab    ", AnsiStripper.strip(MarkdownTableRenderer.align("ab", 6, null)));
    }

    @Test
    void alignEmptyString() {
        assertEquals("      ", AnsiStripper.strip(MarkdownTableRenderer.align("", 6, TableCell.Alignment.LEFT)));
    }

    // ─── preferredWidth ───

    @Test
    void preferredWidthOfPlainText() {
        assertEquals(5, MarkdownTableRenderer.preferredWidth("hello"));
    }

    @Test
    void preferredWidthOfEmptyString() {
        assertEquals(0, MarkdownTableRenderer.preferredWidth(""));
    }

    @Test
    void preferredWidthIgnoresAnsiCodes() {
        var themed = THEME.styled("bold", "hello");
        assertEquals(5, MarkdownTableRenderer.preferredWidth(themed));
    }

    // ─── nextMark ───

    @Test
    void nextMarkFindsHighlight() {
        var mark = MarkdownInline.nextMark("==text==", 0);
        assertNotNull(mark);
        assertEquals(0, mark.start());
        assertEquals("==", mark.marker());
        assertEquals("highlight", mark.slot());
    }

    @Test
    void nextMarkFindsDiffAdd() {
        var mark = MarkdownInline.nextMark("++text++", 0);
        assertNotNull(mark);
        assertEquals(0, mark.start());
        assertEquals("++", mark.marker());
        assertEquals("diffAdd", mark.slot());
    }

    @Test
    void nextMarkFindsDiffDelete() {
        var mark = MarkdownInline.nextMark("--text--", 0);
        assertNotNull(mark);
        assertEquals(0, mark.start());
        assertEquals("--", mark.marker());
        assertEquals("diffDelete", mark.slot());
    }

    @Test
    void nextMarkReturnsNullWhenNoMarkers() {
        assertNull(MarkdownInline.nextMark("plain text", 0));
    }

    @Test
    void nextMarkReturnsNullForEmptyString() {
        assertNull(MarkdownInline.nextMark("", 0));
    }

    @Test
    void nextMarkReturnsNullWhenFromPastEnd() {
        assertNull(MarkdownInline.nextMark("==text==", 100));
    }

    @Test
    void nextMarkFindsEarliestMarker() {
        var mark = MarkdownInline.nextMark("a ==b ++c --d", 0);
        assertNotNull(mark);
        assertEquals(2, mark.start());
        assertEquals("==", mark.marker());
    }

    @Test
    void nextMarkSkipsMarkersBeforeFrom() {
        var mark = MarkdownInline.nextMark("==a ++b", 3);
        assertNotNull(mark);
        assertEquals(4, mark.start());
        assertEquals("++", mark.marker());
    }

    @Test
    void nextMarkAllAtSamePositionReturnsFirstInList() {
        var mark = MarkdownInline.nextMark("==++--", 0);
        assertNotNull(mark);
        assertEquals(0, mark.start());
        assertEquals("==", mark.marker());
    }

    @Test
    void nextMarkFindsMarkersAfterFrom() {
        var mark = MarkdownInline.nextMark("no marks here ==found==", 0);
        assertNotNull(mark);
        assertEquals(14, mark.start());
    }

    // ─── applyTextMarks ───

    @Test
    void applyTextMarksHighlight() {
        var r = renderer(80);
        var raw = r.applyTextMarks("==highlighted==");
        var result = AnsiStripper.strip(raw);
        assertEquals("==highlighted==", result);
        assertTrue(raw.contains(THEME.style("highlight")), "content should be highlighted");
    }

    @Test
    void applyTextMarksDiffAdd() {
        var r = renderer(80);
        var raw = r.applyTextMarks("++added++");
        var result = AnsiStripper.strip(raw);
        assertEquals("++added++", result);
        assertTrue(raw.contains(THEME.style("diffAdd")), "content should be diffAdd styled");
    }

    @Test
    void applyTextMarksDiffDelete() {
        var r = renderer(80);
        var raw = r.applyTextMarks("--deleted--");
        var result = AnsiStripper.strip(raw);
        assertEquals("--deleted--", result);
        assertTrue(raw.contains(THEME.style("diffDelete")), "content should be diffDelete styled");
    }

    @Test
    void applyTextMarksPlainTextUnchanged() {
        var r = renderer(80);
        assertEquals("hello world", AnsiStripper.strip(r.applyTextMarks("hello world")));
    }

    @Test
    void applyTextMarksEmptyString() {
        var r = renderer(80);
        assertEquals("", AnsiStripper.strip(r.applyTextMarks("")));
    }

    @Test
    void applyTextMarksTextBeforeAndAfterMark() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("before ==mid== after"));
        assertEquals("before ==mid== after", result);
    }

    @Test
    void applyTextMarksMultipleMarksOfSameType() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("==a== and ==b=="));
        assertEquals("==a== and ==b==", result);
    }

    @Test
    void applyTextMarksMixedMarkerTypes() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("==hi== ++add++ --del--"));
        assertEquals("==hi== ++add++ --del--", result);
    }

    @Test
    void applyTextMarksUnmatchedOpeningMarkerTreatedAsText() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("==no closing"));
        assertEquals("==no closing", result);
    }

    @Test
    void applyTextMarksUnmatchedMarkerDoesNotSwallowSubsequentValidMarks() {
        var r = renderer(80);
        var raw = r.applyTextMarks("==unmatched ++valid++");
        var result = AnsiStripper.strip(raw);
        assertEquals("==unmatched ++valid++", result);
        assertTrue(raw.contains(THEME.style("diffAdd")),
                                        "++valid++ should be highlighted even after unmatched ==. BUG: unmatched marker swallows rest of string");
    }

    @Test
    void applyTextMarksMarkerAtEndOfString() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("text=="));
        assertEquals("text==", result, "lone marker at end should be plain text");
    }

    @Test
    void applyTextMarksOnlyMarkers() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("===="));
        assertEquals("====", result, "four = with empty content should still render");
    }

    @Test
    void applyTextMarksEmptyContentBetweenMarkers() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("==== end"));
        assertEquals("==== end", result);
    }

    @Test
    void applyTextMarksAdjacentDifferentMarkers() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.applyTextMarks("==hi++bye=="));
        assertEquals("==hi++bye==", result, "nested markers: == wraps everything until next ==");
    }

    // ─── fitWidths ───

    @Test
    void fitWidthsNoChangeWhenSumEqualsAvailable() {
        var widths = new int[] {5, 5, 5};
        MarkdownTableRenderer.fitWidths(widths, 21);
        assertEquals(15, MarkdownTableRenderer.sum(widths));
    }

    @Test
    void fitWidthsShrinksWideColumns() {
        var widths = new int[] {50, 50};
        MarkdownTableRenderer.fitWidths(widths, 30);
        int total = MarkdownTableRenderer.sum(widths);
        assertTrue(total <= 27, "total should fit within available (30 - 3 separator = 27): " + total);
    }

    @Test
    void fitWidthsKeepsNarrowColumnsCompactNotFullWidth() {
        var widths = new int[] {3, 3};
        MarkdownTableRenderer.fitWidths(widths, 50);
        assertEquals(6, MarkdownTableRenderer.sum(widths), "columns keep their content width, not grown to fill 50");
    }

    @Test
    void fitWidthsEnforcesMinThree() {
        var widths = new int[] {0, 0, 0};
        MarkdownTableRenderer.fitWidths(widths, 50);
        for (int w : widths)
            assertTrue(w >= 3, "each column should be at least 3: " + widths[0] + "," + widths[1] + "," + widths[2]);
    }

    @Test
    void fitWidthsSingleColumnStaysContentSized() {
        var widths = new int[] {5};
        MarkdownTableRenderer.fitWidths(widths, 80);
        assertEquals(5, MarkdownTableRenderer.sum(widths),
                                        "a single narrow column is not stretched across the terminal");
    }

    @Test
    void fitWidthsNarrowTerminalGracefulDegradation() {
        var widths = new int[] {10, 10, 10};
        MarkdownTableRenderer.fitWidths(widths, 10);
        for (int w : widths)
            assertTrue(w >= 3 && w <= 5, "columns should be clamped to 3-5 for very narrow terminal: " + w);
    }

    @Test
    void fitWidthsShrinksWidestColumnFirst() {
        var widths = new int[] {5, 30, 5};
        MarkdownTableRenderer.fitWidths(widths, 40);
        assertTrue(widths[1] < 30, "widest column should be shrunk: " + widths[1]);
        assertEquals(5, widths[0], "narrow columns should not be shrunk below their content");
    }

    @Test
    void fitWidthsDoesNotShrinkBelowFiveDuringNarrowing() {
        var widths = new int[] {5, 5, 5};
        MarkdownTableRenderer.fitWidths(widths, 5);
        for (int w : widths)
            assertTrue(w >= 5, "columns at 5 should not be shrunk further: " + w);
    }

    @Test
    void fitWidthsKeepsContentSizedColumnsUnchangedWhenTheyFit() {
        var widths = new int[] {3, 3, 3};
        MarkdownTableRenderer.fitWidths(widths, 30); // fits within 30 → unchanged (no growth)
        assertEquals(9, MarkdownTableRenderer.sum(widths), "content-sized columns that fit are left as-is");
    }

    // ─── separator ───

    @Test
    void separatorSingleColumn() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.separator(new int[] {5}));
        assertEquals("─────", result);
    }

    @Test
    void separatorMultipleColumns() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.separator(new int[] {3, 5}));
        assertEquals("────┼──────", result);
    }

    @Test
    void separatorThreeColumns() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.separator(new int[] {2, 3, 4}));
        assertEquals("───┼─────┼─────", result);
    }

    @Test
    void separatorZeroWidth() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.separator(new int[] {0}));
        assertEquals("", result);
    }

    @Test
    void separatorEmptyArray() {
        var r = renderer(80);
        var result = AnsiStripper.strip(r.separator(new int[0]));
        assertEquals("", result);
    }

    // ─── wrapStyled ───

    @Test
    void wrapStyledNullReturnsEmptyList() {
        var result = MarkdownTableRenderer.wrapStyled(null, 10);
        assertEquals(List.of(""), result);
    }

    @Test
    void wrapStyledEmptyReturnsEmptyList() {
        var result = MarkdownTableRenderer.wrapStyled("", 10);
        assertEquals(List.of(""), result);
    }

    @Test
    void wrapStyledShortTextReturnsSingleLine() {
        var result = MarkdownTableRenderer.wrapStyled("hello", 10);
        assertEquals(1, result.size());
        assertEquals("hello", AnsiStripper.strip(result.get(0)));
    }

    @Test
    void wrapStyledLongTextWrapsToMultipleLines() {
        var result = MarkdownTableRenderer.wrapStyled("hello world foo bar", 5);
        assertTrue(result.size() > 1, "should wrap to multiple lines: " + result);
    }

    @Test
    void wrapStyledWidthZeroClampsToOne() {
        var result = MarkdownTableRenderer.wrapStyled("hello", 0);
        assertFalse(result.isEmpty());
        for (var line : result)
            assertTrue(AnsiWidth.visibleWidth(line) <= 1 || line.isEmpty(),
                                            "each line should be at most 1 visible char: " + AnsiStripper.strip(line));
    }

    @Test
    void wrapStyledNegativeWidthClampsToOne() {
        var result = MarkdownTableRenderer.wrapStyled("hello", -1);
        assertFalse(result.isEmpty());
    }

    @Test
    void wrapStyledExactFitReturnsSingleLine() {
        var result = MarkdownTableRenderer.wrapStyled("hello", 5);
        assertEquals(1, result.size());
    }

    // ─── parameterized align tests ───

    @ParameterizedTest
    @CsvSource({"left, 10, LEFT", "right, 10, RIGHT", "center, 10, CENTER", "x, 3, LEFT", "x, 3, RIGHT"})
    void alignAlwaysProducesCorrectVisibleWidth(String text, int width, TableCell.Alignment alignment) {
        var result = AnsiStripper.strip(MarkdownTableRenderer.align(text, width, alignment));
        assertEquals(Math.max(text.length(), width), result.length(),
                                        "aligned text should fill the column width: '" + result + "'");
    }
}
