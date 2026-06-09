package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiffRendererTest {
    @Test
    void rendersAdditionAndDeletionHunks() {
        var renderer = new DiffRenderer(AnsiTheme.dark());
        var lines = renderer.render("edit", "file.txt", "old", "new", 80);

        assertFalse(lines.isEmpty());
        assertTrue(lines.getFirst().contains("file.txt"));
        assertTrue(lines.stream().anyMatch(l -> AnsiStripper.strip(l).trim().startsWith("+")));
        assertTrue(lines.stream().anyMatch(l -> AnsiStripper.strip(l).trim().startsWith("-")));
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
        assertTrue(header.contains("edit"));
        assertTrue(header.contains("Main.java"));
        assertTrue(header.contains("+1"));
        assertTrue(header.contains("-1"));
    }
}
