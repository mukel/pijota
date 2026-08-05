package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rich renderer consumes markdown delimiters and styles the text; barebones {@code TerminalMarkdownRenderer} keeps
 * them.
 */
class RichMarkdownRendererTest {
    private static final AnsiTheme THEME = AnsiTheme.dark();

    private static List<StyledLine> render(String md) {
        var r = new RichMarkdownRenderer(THEME);
        r.setWidth(80);
        return r.render(md, "text", false);
    }

    /** Visible text with all ANSI (SGR + OSC 8) removed, one entry per rendered line. */
    private static List<String> lines(String md) {
        return render(md).stream().map(l -> strip(l.text())).toList();
    }

    private static String visible(String md) {
        return String.join("\n", lines(md));
    }

    private static String raw(String md) {
        return render(md).stream().map(StyledLine::text).collect(joining("\n"));
    }

    private static String strip(String s) {
        return s.replaceAll("\\x1b\\[[0-9;]*m", "").replaceAll("\\x1b\\]8;;[^\\x1b]*\\x1b\\\\", "");
    }

    // ---- headings ----

    @Test
    void headingConsumesHashAndUnderlinesH1() {
        assertEquals("Heading", visible("# Heading"));
        assertEquals("Heading", visible("## Heading"));
        assertEquals("Heading", visible("###### Heading"));
        assertTrue(raw("# H").contains("\033[4m"), "h1 is underlined");
        assertFalse(raw("## H").contains("\033[4m"), "only h1 is underlined");
    }

    // ---- inline emphasis ----

    @Test
    void emphasisAndCodeConsumeDelimiters() {
        assertEquals("bold italic struck code", visible("**bold** *italic* ~~struck~~ `code`"));
    }

    @Test
    void nestedEmphasisConsumesDelimiters() {
        assertEquals("bold italic", visible("***bold italic***"));
        assertEquals("all three", visible("**~~*all three*~~**"));
    }

    // ---- links ----

    @Test
    void linkStripsSyntaxShowsUrlAndEmitsHyperlink() {
        assertEquals("text (https://x.com)", visible("[text](https://x.com)"));
        assertTrue(raw("[text](https://x.com)").contains("\033]8;;https://x.com"), "OSC 8 hyperlink emitted");
    }

    @Test
    void linkWhoseTextEqualsUrlIsNotDuplicated() {
        assertEquals("https://x.com", visible("[https://x.com](https://x.com)"));
    }

    // ---- lists ----

    @Test
    void unorderedBulletsUseGlyph() {
        assertEquals("⦁ item", visible("- item"));
        assertEquals("⦁ item", visible("* item"));
        assertEquals("⦁ item", visible("+ item"));
    }

    @Test
    void orderedListsKeepTheirNumber() {
        assertEquals("1. first", visible("1. first"));
        assertEquals("7. seventh", visible("7. seventh"));
    }

    @Test
    void listItemInlineMarkdownIsStyled() {
        assertEquals("⦁ item with bold and code", visible("- item with **bold** and `code`"));
    }

    @Test
    void nestedListLinePreservesIndentWhenStreamedAlone() {
        assertEquals("  ⦁ nested", visible("  - nested"));
        assertEquals("   1. deep", visible("   1. deep"));
    }

    @Test
    void deeplyIndentedListLineRendersAsListNotCode() {
        // 5-space indent parses as an indented code block on its own; the renderer dedents-then-reindents so it stays
        // a styled list item (bullet + consumed ~~strikethrough~~), not raw "- ... ~~...~~".
        assertEquals("     ⦁ Andromeda colliding soon", visible("     - Andromeda ~~colliding~~ soon"));
    }

    // ---- checklists ----

    @Test
    void checklistMarkersRenderAsStyledBoxes() {
        assertEquals("✓ done", visible("- [x] done"));
        assertEquals("✓ done", visible("- [X] done"));
        assertEquals("☐ todo", visible("- [ ] todo"));
        assertEquals("◐ wip", visible("- [~] wip"));
    }

    // ---- blockquotes ----

    @Test
    void blockquoteReplacesMarkerWithBar() {
        assertEquals("│ quote", visible("> quote"));
    }

    @Test
    void emptyBlockquoteLineRendersBar() {
        assertEquals("│", visible(">"));
    }

    @Test
    void blockquoteInlineMarkdownIsStyled() {
        assertEquals("│ bold and code", visible("> **bold** and `code`"));
    }

    // ---- thematic break ----

    @Test
    void thematicBreakRendersHorizontalRule() {
        String hr = visible("---");
        assertFalse(hr.isBlank());
        assertTrue(hr.chars().allMatch(c -> c == '─'), "rule is all box-drawing dashes: " + hr);
    }

    // ---- GFM text marks ----

    @Test
    void textMarksConsumeDelimiters() {
        assertEquals("high ins del", visible("==high== ++ins++ --del--"));
    }

    @Test
    void unpairedDashesStayLiteral() {
        assertEquals("range 10--20 stays", visible("range 10--20 stays"));
    }

    // ---- tables (delegated, rich cells) ----

    @Test
    void tableCellsAreRichRendered() {
        String out = visible("| A | B |\n|---|---|\n| **bold** | `code` |");
        assertTrue(out.contains("│"), "renders as a table: " + out);
        assertTrue(out.contains("bold") && out.contains("code"), out);
        assertFalse(out.contains("**") || out.contains("`"), "cell delimiters consumed: " + out);
    }

    @Test
    void indentedTableStillRenders() {
        String out = visible("    | A | B |\n    |---|---|\n    | x | y |");
        assertTrue(out.contains("│"), "an indented (nested) table still renders as a table: " + out);
    }

    // ---- edge ----

    @Test
    void whitespaceOnlyLineDoesNotCrash() {
        assertDoesNotThrow(() -> render("   "));
    }

    @Test
    void plainRendererStillKeepsDelimiters() {
        var plain = new TerminalMarkdownRenderer(THEME);
        plain.setWidth(80);
        String out = plain.render("# H and **b**", "text", false).stream().map(l -> strip(l.text()))
                                        .collect(joining("\n"));
        assertTrue(out.contains("#") && out.contains("**"), "barebones renderer keeps delimiters: " + out);
    }
}
