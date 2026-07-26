package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiffRendererTest {
    @Test
    void rendersNonEmptyDiffForChangedContent() {
        var renderer = new DiffRenderer(AnsiTheme.dark());
        var lines = renderer.render("edit", "file.txt", "line1\nline2\nline3\n", "line1\nmodified\nline3\n", 80);

        assertFalse(lines.isEmpty(), "diff should produce output for changed content");
        assertTrue(lines.getFirst().contains("file.txt"));
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

        var header = AnsiStripper.strip(lines.getFirst());
        assertTrue(header.contains("edit"), "expected 'edit' in header: " + header);
        assertTrue(header.contains("Main.java"), "expected 'Main.java' in header: " + header);
        assertTrue(header.contains("+1"), "expected '+1' in header: " + header);
        assertTrue(header.contains("-1"), "expected '-1' in header: " + header);
    }
}
