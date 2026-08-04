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
