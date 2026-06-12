package ai.pijota.tui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThemeManagerTest {

    @Test
    void themeNameStripsJsonExtension() {
        assertEquals("dark", ThemeManager.themeName(Path.of("dark.json")));
    }

    @Test
    void themeNameWithoutExtension() {
        assertEquals("custom", ThemeManager.themeName(Path.of("custom")));
    }

    @Test
    void themeNameWithPath() {
        assertEquals("light", ThemeManager.themeName(Path.of("/home/user/.pijota/themes/light.json")));
    }

    @Test
    void themeNameEmptyJson() {
        assertEquals("", ThemeManager.themeName(Path.of(".json")));
    }
}
