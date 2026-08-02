package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import java.util.List;
import org.junit.jupiter.api.Test;

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
