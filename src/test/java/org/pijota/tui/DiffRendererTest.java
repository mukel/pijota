package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffRendererTest {

    /** The block opens with its line of air, so the header is the row after it. */
    private static String header(List<String> lines) {
        return AnsiStripper.strip(lines.get(1));
    }

    @Test
    void rendersNonEmptyDiffForChangedContent() {
        var renderer = new DiffRenderer(AnsiTheme.dark());
        var lines = renderer.render("edit", "file.txt", "line1\nline2\nline3\n", "line1\nmodified\nline3\n", 80);

        assertFalse(lines.isEmpty(), "diff should produce output for changed content");
        assertTrue(header(lines).contains("file.txt"));
    }

    @Test
    void returnsEmptyListWhenNoChanges() {
        var renderer = new DiffRenderer(AnsiTheme.dark());
        var lines = renderer.render("edit", "f.txt", "same", "same", 80);

        assertTrue(lines.isEmpty());
    }

    @Test
    void headerContainsActionCountsAndPath() {
        var renderer = new DiffRenderer(AnsiTheme.dark());
        var lines = renderer.render("edit", "src/Main.java", "a\nb", "a\nc", 80);

        var header = header(lines);
        assertTrue(header.contains("edit"), "expected 'edit' in header: " + header);
        assertTrue(header.contains("Main.java"), "expected 'Main.java' in header: " + header);
        assertTrue(header.contains("+1"), "expected '+1' in header: " + header);
        assertTrue(header.contains("-1"), "expected '-1' in header: " + header);
    }

    private static List<String> render(String oldText, String newText, int width) {
        return new DiffRenderer(AnsiTheme.dark()).render("edit", "X.java", oldText, newText, width);
    }

    private static List<String> plain(List<String> lines) {
        return lines.stream().map(AnsiStripper::strip).toList();
    }

    /**
     * Splitting a file keeps the empty string after its final newline, and that is not a line. Counting it made a five
     * line file five insertions everywhere except here, where it was six, and drew a row numbered one past the end of
     * the file at the foot of every diff.
     */
    @Test
    void theEmptyStringAfterAFinalNewlineIsNotALine() {
        var lines = render("", "package a;\n\npublic class A {\n    void run() {}\n}\n", 74);

        assertTrue(plain(lines).get(1).contains("+5"), "five lines is five insertions, as git counts it: " + lines);
        assertEquals("    5 + }", plain(lines).get(lines.size() - 1).stripTrailing(),
                                        () -> "the last row is the last line of the file: " + plain(lines));
    }

    /** The same, from the other end: no diff ends on a phantom row numbered past the file. */
    @Test
    void noDiffEndsOnARowPastTheEndOfTheFile() {
        var lines = plain(render("if (a < b) {\n", "if (a <= b) {\n", 74));

        assertEquals(4, lines.size(), () -> "expected air, header and the two changed rows, got " + lines);
        assertTrue(lines.get(3).contains("if (a <= b) {"), lines.get(3));
    }

    /**
     * The trailing empty string is dropped only when both files agree about having one. When they disagree it is the
     * only thing that carries the change, and whether a file ends with a newline is a real property of the file.
     */
    @Test
    void gainingOrLosingTheFinalNewlineIsStillVisible() {
        assertTrue(plain(render("a\nb", "a\nb\n", 74)).get(1).contains("+1"), "gaining one is an addition");
        assertTrue(plain(render("a\nb\n", "a\nb", 74)).get(1).contains("-1"), "losing one is a removal");
        assertTrue(render("a\nold", "a\nnew", 74).size() > 0, "and neither file having one changes nothing");
    }

    /**
     * A blank line between hunks says there is a gap without saying it skipped anything, so the numbers either side
     * read as contiguous until someone checks them. The separator's whole job is to name the count.
     */
    @Test
    void theGapBetweenHunksNamesHowManyLinesItSkipped() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n";

        // The newlines are part of the needle so that "2" does not also match inside "12".
        var far = plain(render(before, before.replace("\n2\n", "\nTWO\n").replace("\n14\n", "\nFOURTEEN\n"), 74));
        assertTrue(far.stream().anyMatch(l -> l.contains("⋮ 5 unchanged lines")),
                                        () -> "expected a counted gap: " + far);

        var near = plain(render(before, before.replace("\n2\n", "\nTWO\n").replace("\n10\n", "\nTEN\n"), 74));
        assertTrue(near.stream().anyMatch(l -> l.contains("⋮ 1 unchanged line")),
                                        () -> "one line reads in the singular: " + near);
    }

    /**
     * The cap counts rows drawn, and context rows are drawn too, so a diff cut at the cap has shown fewer changed rows
     * than the cap. Comparing the two directly claimed to show more changed rows than the diff contained.
     */
    @Test
    void truncationCountsTheChangedRowsItDidNotShow() {
        var before = new StringBuilder();
        var after = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            before.append("line ").append(i).append(";\n");
            after.append(i % 5 == 0 ? "CHANGED " + i + ";\n" : "line " + i + ";\n");
        }
        var lines = plain(render(before.toString(), after.toString(), 74));

        String header = lines.get(1);
        String note = lines.get(lines.size() - 1);
        assertTrue(note.contains("truncated"), () -> "expected a truncation note, got " + note);
        // 100 changed lines, so 200 changed rows; the cap draws 4 context + 1 delete + 1 add per 5 source lines, so
        // exactly half of them fit. The note must describe the half that did not.
        assertTrue(header.contains("+100") && header.contains("-100"), header);
        // Exact, not contains: subtracting the wrong counter yields "-100 more…", which contains "100 more…".
        assertEquals("… diff truncated: 100 more changed rows not shown", note.strip(),
                                        () -> "inaccurate count: " + note);
    }

    /** Which columns of a rendered row carry any background at all, by tracking what is in force. */
    private static String backgroundMap(String line) {
        var out = new StringBuilder();
        boolean on = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == 0x1b) {
                int end = line.indexOf('m', i);
                String esc = line.substring(i, end + 1);
                if (esc.equals("\033[0m"))
                    on = false;
                else if (esc.contains("48;"))
                    on = true;
                i = end;
                continue;
            }
            out.append(on ? '#' : '.');
        }
        return out.toString();
    }

    private static String rowContaining(List<String> lines, String text) {
        return lines.stream().filter(l -> AnsiStripper.strip(l).contains(text)).findFirst().orElseThrow();
    }

    /**
     * A changed row is a band of colour, and the marker is part of what the band is saying. The gutter is two styled
     * runs and a bare space, and each styled run closes with a full reset that took the row background down with it, so
     * the marker and the space after it were the only cells of the row without one: a two column notch bitten out of
     * the highlight, exactly where the eye goes to read the sign.
     */
    @Test
    void everyCellOfAChangedRowCarriesTheRowBackground() {
        var lines = new DiffRenderer(AnsiTheme.dark()).render("edit", "X.java", "keep;\nold value;\ntail;\n",
                                        "keep;\nnew value;\ntail;\n", 44);

        for (String text : List.of("old value;", "new value;")) {
            String map = backgroundMap(rowContaining(lines, text));
            assertFalse(map.contains("."), () -> "a changed row has a gap in its background:\n  " + map + "\n  "
                                            + AnsiStripper.strip(rowContaining(lines, text)));
        }
    }

    /**
     * The continuation of a wrapped changed row is the same band, including the indent that stands in for the gutter.
     */
    @Test
    void aWrappedChangedRowKeepsTheBackgroundOnItsContinuation() {
        var lines = new DiffRenderer(AnsiTheme.dark()).render("edit", "X.java", "keep;\n" + "alpha ".repeat(12) + "\n",
                                        "keep;\n" + "omega ".repeat(12) + "\n", 40);

        String continuation = backgroundMap(rowContaining(lines, "omega omega omega"));
        assertFalse(continuation.contains("."), () -> "a wrapped continuation has a gap: " + continuation);
    }

    /** Context rows are not part of any change, so they carry no band and must not grow one. */
    @Test
    void aContextRowCarriesNoBackgroundAtAll() {
        var lines = new DiffRenderer(AnsiTheme.dark()).render("edit", "X.java", "keep;\nold;\n", "keep;\nnew;\n", 44);

        String map = backgroundMap(rowContaining(lines, "keep;"));
        assertFalse(map.contains("#"), () -> "an unchanged row should be plain: " + map);
    }

    @Test
    void aLongPathKeepsTheHeaderOnOneRow() {
        // An absolute path longer than the terminal used to wrap mid-path, so the header broke across two rows and
        // the second started with the middle of a directory name, right above the diff it was supposed to label.
        String path = "/tmp/claude-1000/-home-user-Desktop-playground-project/f6b43f9b-5eff-4e71-a5ec-f2eef4c490ba"
                                        + "/scratchpad/uidemo/calc.py";
        var lines = new DiffRenderer(AnsiTheme.dark()).render("edit preview", path, "a\n", "a\nb\n", 96);

        String header = header(lines);
        assertTrue(AnsiWidth.visibleWidth(header) <= 96, "header overflows: [" + header + "]");
        assertTrue(header.startsWith("edit preview "), header);
        assertTrue(header.contains("calc.py"), "the filename is the end that matters: " + header);
        assertTrue(header.contains("…"), "the middle is what gets dropped: " + header);
        assertTrue(header.endsWith("─"), "the header still ends in a rule: " + header);
    }

}
