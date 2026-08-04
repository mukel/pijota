package org.pijota.tui;

import org.pijota.theme.AnsiTheme;
import org.pijota.util.DiffAlgorithm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Word emphasis as it is actually painted: the right characters, in the right columns, surviving the syntax colouring
 * underneath it and the wrapping applied after it.
 *
 * <p>
 * These assert the columns rather than the escapes. "The output contains a background code" passes whether the
 * highlight lands on the changed word or three columns to the left of it, which is the bug worth catching.
 */
class DiffWordEmphasisTest {

    private static final Pattern ESCAPE = Pattern.compile("\033\\[[0-9;]*m");
    private static final AnsiTheme THEME = AnsiTheme.dark();

    private static List<String> render(String oldText, String newText, int width) {
        return new DiffRenderer(THEME).render("edit", "X.java", oldText, newText, width);
    }

    /** The visible text of a line, with every escape removed. */
    private static String plain(String line) {
        return ESCAPE.matcher(line).replaceAll("");
    }

    /**
     * The columns of one rendered line that carry the word-emphasis background, by walking the escapes and tracking
     * which background is in force. This is the reader's eye, expressed as an assertion.
     */
    private static String emphasised(String line, String wordBackground) {
        var out = new StringBuilder();
        boolean on = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == 0x1b) {
                int end = line.indexOf('m', i);
                String esc = line.substring(i, end + 1);
                if (esc.equals(wordBackground))
                    on = true;
                else if (esc.equals("\033[0m") || isBackground(esc))
                    on = false;
                i = end;
                continue;
            }
            if (on)
                out.append(ch);
        }
        return out.toString();
    }

    private static boolean isBackground(String esc) {
        return esc.contains("48;") || esc.matches("\033\\[[34][0-9]m");
    }

    /**
     * The added or removed row of a one-line change. When only whitespace moved, both rows carry the same visible text,
     * so naming a row by its content picks whichever came first: the marker is the only thing that tells them apart.
     */
    private static String markedRow(List<String> lines, char marker) {
        return lines.stream().filter(l -> {
            String p = plain(l).stripTrailing();
            int at = p.indexOf(marker);
            // the marker sits in the gutter, after the line numbers and before the code
            return at > 0 && at < 10 && p.substring(0, at).chars().allMatch(c -> c == ' ' || Character.isDigit(c));
        }).findFirst().orElseThrow(() -> new AssertionError("no '" + marker + "' row in:\n  " + String.join("\n  ",
                                        lines.stream().map(DiffWordEmphasisTest::plain).toList())));
    }

    /** The one line of the diff that shows {@code text}, so a test can name the row it means. */
    private static String rowContaining(List<String> lines, String text) {
        return lines.stream().filter(l -> plain(l).contains(text)).findFirst()
                                        .orElseThrow(() -> new AssertionError("no rendered row contains [" + text
                                                                        + "] in:\n  " + String.join("\n  ", lines)));
    }

    @Test
    void onlyTheChangedIdentifierIsEmphasisedOnTheAddedRow() {
        var lines = render("int oldName = compute(a, b);\n", "int newName = compute(a, b);\n", 100);

        String added = rowContaining(lines, "newName");
        assertEquals("newName", emphasised(added, THEME.style("diffAddWord")),
                                        "the highlight must cover the changed word and nothing else: " + plain(added));
    }

    @Test
    void theDeletedRowEmphasisesWhatItLost() {
        var lines = render("call(a, b);\n", "call(a);\n", 100);

        String deleted = rowContaining(lines, "call(a, b);");
        assertEquals(", b", emphasised(deleted, THEME.style("diffDeleteWord")), plain(deleted));
    }

    /** Syntax colour runs underneath. Its resets must not take the emphasis with them. */
    @Test
    void emphasisSurvivesTheSyntaxColouringItSitsOn() {
        var lines = render("int x = alpha(1);\n", "int x = omega(1);\n", 100);

        String added = rowContaining(lines, "omega");
        assertEquals("omega", emphasised(added, THEME.style("diffAddWord")),
                                        "a syntax reset in the middle of the word dropped the background: "
                                                                        + added.replace("\033", "ESC"));
    }

    /**
     * Wrapping splits the row after the spans were computed. Every continuation must resume at the offset the previous
     * fragment reached, or the emphasis restarts at column zero and lands on whatever is there.
     */
    @Test
    void emphasisLandsCorrectlyOnAWrappedRow() {
        String prefix = "int value = compute(alpha, beta, gamma, delta, epsilon, zeta, eta, theta, iota, kappa";
        var lines = render(prefix + ", oldTail);\n", prefix + ", newTail);\n", 60);

        String joined = lines.stream().map(l -> emphasised(l, THEME.style("diffAddWord"))).reduce("", String::concat);
        assertEquals("newTail", joined, "across every fragment, exactly the changed word is emphasised:\n  " + String
                                        .join("\n  ", lines.stream().map(DiffWordEmphasisTest::plain).toList()));
    }

    /** A rewrite gets no word emphasis, so the row reads exactly as it did before the feature existed. */
    @Test
    void aRewrittenLineCarriesNoWordEmphasis() {
        var lines = render("int total = sum(values);\n", "log.warn(\"nothing matches\");\n", 100);

        for (String line : lines) {
            assertEquals("", emphasised(line, THEME.style("diffAddWord")), plain(line));
            assertEquals("", emphasised(line, THEME.style("diffDeleteWord")), plain(line));
        }
    }

    /** Context rows are not part of any pair and must stay unpainted. */
    @Test
    void contextRowsAreNeverEmphasised() {
        var lines = render("keep me\nold value\nkeep me too\n", "keep me\nnew value\nkeep me too\n", 100);

        String context = rowContaining(lines, "keep me too");
        assertEquals("", emphasised(context, THEME.style("diffAddWord")), plain(context));
        assertEquals("", emphasised(context, THEME.style("diffDeleteWord")), plain(context));
    }

    /** The row background still reaches the edge; word emphasis nests inside it rather than replacing it. */
    @Test
    void theRowBackgroundSurvivesAlongsideTheWordBackground() {
        String added = rowContaining(render("int a = 1;\n", "int b = 1;\n", 100), "int b = 1;");

        assertTrue(added.contains(THEME.style("diffAddLine")), "the row background is gone");
        assertTrue(added.contains(THEME.style("diffAddWord")), "the word background is gone");
    }

    /** Whatever the width, the rendered text is the file's text: emphasis must never insert or drop a character. */
    @ParameterizedTest
    @ValueSource(ints = {40, 60, 80, 120, 200})
    void emphasisNeverAltersTheTextAtAnyWidth(int width) {
        var lines = render("int oldName = compute(a, b);\n", "int newName = compute(a, b);\n", width);

        String all = lines.stream().map(DiffWordEmphasisTest::plain).reduce("", (a, b) -> a + b);
        assertTrue(all.replace(" ", "").contains("newName"), "the added line lost text at width " + width);
        assertTrue(all.replace(" ", "").contains("oldName"), "the deleted line lost text at width " + width);
    }

    /** Pairing is what makes a pair; a lone insertion has nothing to be compared against. */
    @Test
    void anInsertionWithNoCounterpartIsNotEmphasised() {
        var lines = render("first\nsecond\n", "first\ninserted\nsecond\n", 100);

        String inserted = rowContaining(lines, "inserted");
        assertEquals("", emphasised(inserted, THEME.style("diffAddWord")),
                                        "nothing was replaced, so there is no word-level story to tell");
    }

    @Test
    void pairingMatchesEqualRunsAndLeavesTheSurplusAlone() {
        var rows = DiffAlgorithm.diff("a\nb\n", "x\ny\nz\n");
        var pairs = DiffAlgorithm.pairs(rows);

        var deletes = new ArrayList<Integer>();
        var adds = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind() == DiffAlgorithm.Kind.DELETE)
                deletes.add(i);
            if (rows.get(i).kind() == DiffAlgorithm.Kind.ADD)
                adds.add(i);
        }
        assertEquals(adds.get(0), pairs.get(deletes.get(0)));
        assertEquals(adds.get(1), pairs.get(deletes.get(1)));
        assertNull(pairs.get(adds.get(2)), "the third addition replaced nothing");
    }

    /**
     * The no-colour theme is what non-terminal frontends and colourless terminals get. Emphasis that cannot be seen
     * must also not be spelled: an escape emitted here would land as literal junk in the middle of the code.
     */
    @Test
    void thePlainThemeEmitsNoEscapesAtAll() {
        var lines = new DiffRenderer(AnsiTheme.plain()).render("edit", "X.java", "int oldName = compute(a);\n",
                                        "int newName = compute(a);\n", 100);

        for (String line : lines)
            assertEquals(line, plain(line), "the colourless theme emitted an escape: " + line.replace("\033", "ESC"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("newName")), "fixture check: the diff still renders");
    }

    /**
     * Every bundled theme needs its own word backgrounds. The built-in defaults are tuned for a dark terminal, so a
     * light theme falling back to them would paint dark green and dark red onto a pale row: unreadable exactly where
     * the reader is being asked to look.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dark", "light", "gruvbox-dark", "gruvbox-light"})
    void everyBundledThemeDefinesItsOwnWordBackgrounds(String name) throws Exception {
        var json = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/themes/" + name + ".json"));

        assertTrue(json.contains("toolDiffAddedBg"), name + " falls back to the dark-tuned default for added words");
        assertTrue(json.contains("toolDiffRemovedBg"),
                                        name + " falls back to the dark-tuned default for removed words");
    }

    /** And the word background has to differ from the row's, or the emphasis is invisible. */
    @ParameterizedTest
    @ValueSource(strings = {"dark", "light"})
    void theWordBackgroundIsDistinctFromTheRowBackground(String name) {
        var theme = "dark".equals(name) ? AnsiTheme.dark() : AnsiTheme.light();

        assertNotEquals(theme.style("diffAddLine"), theme.style("diffAddWord"), name + ": added words vanish");
        assertNotEquals(theme.style("diffDeleteLine"), theme.style("diffDeleteWord"), name + ": removed words vanish");
    }

    // ---------- whitespace and tabs, as painted ----------

    /**
     * Line terminators are not compared, so converting a file between CRLF and LF changes no line and the renderer
     * draws nothing. Pinned here because "no diff" then means "only the terminators moved", which is worth knowing.
     */
    @Test
    void aLineEndingConversionRendersNothingAtAll() {
        assertTrue(render("a\nb\n", "a\r\nb\r\n", 100).isEmpty(),
                                        "nothing changed line for line, so there is nothing to draw");
    }

    /**
     * The change a reader can least afford to miss and can least easily see. Foreground emphasis could not show it at
     * all: there is no glyph to recolour. A background can.
     */
    @Test
    void trailingWhitespaceIsMadeVisibleByTheBackground() {
        var lines = render("int x = 1;\n", "int x = 1;   \n", 100);

        String added = markedRow(lines, '+');
        assertEquals("   ", emphasised(added, THEME.style("diffAddWord")),
                                        "the three added spaces are the whole change and must be painted");
    }

    @Test
    void retabbingIsMadeVisibleByTheBackground() {
        var lines = render("\tif (a) {\n", "    if (a) {\n", 100);

        String added = markedRow(lines, '+');
        assertEquals("    ", emphasised(added, THEME.style("diffAddWord")), "the new indentation is the change");
    }

    /**
     * Tabs never reach the terminal: the wrapper turns each into spaces to a four-column stop, because a tab moves the
     * cursor without overwriting what it skips. Emphasis has to land on the spaces that replaced it.
     */
    @Test
    void emphasisOnATabIndentedLineLandsOnTheExpandedSpaces() {
        var lines = render("\tfmt.Println(\"old\")\n", "\tfmt.Println(\"new\")\n", 100);

        String added = rowContaining(lines, "new");
        assertEquals("new", emphasised(added, THEME.style("diffAddWord")), plain(added));
        assertFalse(plain(added).contains("\t"), "a raw tab reached the terminal and will not overwrite what it skips");
    }

    /**
     * The row background reaches one column past the text, marking where the change ends. Painting the body before
     * wrapping once dropped it, because the painted body ends with a reset and the trailing pad came after.
     */
    @Test
    void theRowBackgroundExtendsPastTheEndOfTheText() {
        String added = rowContaining(render("int a = 1;\n", "int b = 1;\n", 100), "int b = 1;");

        int lastReset = added.lastIndexOf("\033[0m");
        String tail = added.substring(0, lastReset);
        assertTrue(tail.endsWith(THEME.style("diffAddLine") + " "), "the row should end with its background and a pad: "
                                        + added.replace("\033", "ESC"));
    }

    /**
     * A line that is nothing but blank changing from spaces to a tab. Two rows that render identically, and the pair
     * shares no token at all, so the similarity gate would refuse it: without the whitespace bypass the diff reports a
     * change and gives the reader no way to see it. The strongest case for painting with a background.
     */
    @Test
    void aWhitespaceOnlyLineStillShowsWhatChanged() {
        var lines = render("if (a) {\n    \n}\n", "if (a) {\n\t\n}\n", 100);

        assertEquals("    ", emphasised(markedRow(lines, '-'), THEME.style("diffDeleteWord")),
                                        "the old blank line is the whole of what was removed");
        assertEquals("    ", emphasised(markedRow(lines, '+'), THEME.style("diffAddWord")),
                                        "and the tab, expanded to spaces, is the whole of what replaced it");
    }

    /**
     * The trailing pad is appended after wrapping, so the wrap has to leave room for it. Without that a row which fills
     * the terminal exactly comes out one column too wide, the terminal wraps the overflow, and a blank line appears in
     * the middle of a wrapped row. Found by measuring rendered rows against the width they were asked for.
     */
    @ParameterizedTest
    @ValueSource(ints = {40, 50, 60, 80, 100, 132})
    void noRenderedRowIsEverWiderThanTheTerminal(int width) {
        var lines = render("var x = compute(alpha, beta, gamma, delta, epsilon, zeta, eta, oldTail);\n",
                                        "var x = compute(alpha, beta, gamma, delta, epsilon, zeta, eta, newTail);\n",
                                        width);

        for (String line : lines) {
            int rendered = AnsiWidth.visibleWidth(line);
            assertTrue(rendered <= width, "a row " + rendered + " columns wide at width " + width
                                            + " will be split by the terminal: " + plain(line));
        }
    }
}
