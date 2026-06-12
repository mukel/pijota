package ai.pijota.tui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void switchToBuiltinTheme() {
        var host = new FakeHost(ai.pijota.theme.AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchTo("dark");

        assertEquals("dark", host.theme.name);
        assertTrue(host.statuses.stream().anyMatch(s -> s.contains("switched")),
                                        "expected status message about switching theme, got " + host.statuses);
    }

    @Test
    void switchToMissingBuiltinReportsError() {
        var host = new FakeHost(ai.pijota.theme.AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchTo("definitely-missing-theme");

        assertTrue(host.errors.stream().anyMatch(e -> e.contains("theme not found")),
                                        "expected error message about missing theme, got " + host.errors);
    }

    @Test
    void switchToPathAbsolute(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("custom.json");
        Files.writeString(file, "{\"name\":\"custom\",\"enabled\":true,\"text\":\"#ffffff\"}");

        var host = new FakeHost(ai.pijota.theme.AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchToPath(file.toString());

        assertEquals("custom", host.theme.name);
        assertTrue(host.statuses.stream().anyMatch(s -> s.contains("switched")),
                                        "expected status message about switching theme, got " + host.statuses);
    }

    @Test
    void switchToPathMissingReportsError() {
        var host = new FakeHost(ai.pijota.theme.AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchToPath("/nonexistent/theme/file.json");

        assertTrue(host.errors.stream().anyMatch(e -> e.contains("theme not found")),
                                        "expected error message about missing theme path, got " + host.errors);
    }

    @Test
    void switchToPathInvalidThemeReportsLoadError(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("bad.json");
        Files.writeString(file, "not json");

        var host = new FakeHost(ai.pijota.theme.AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchToPath(file.toString());

        assertTrue(host.errors.stream().anyMatch(e -> e.contains("theme load failed")),
                                        "expected error message about load failure, got " + host.errors);
    }

    @Test
    void discoverListsBuiltinsAndFileThemes(@TempDir Path tmp) throws Exception {
        var themesDir = tmp.resolve(".pijota").resolve("themes");
        Files.createDirectories(themesDir);
        Files.writeString(themesDir.resolve("mytheme.json"),
                                        "{\"name\":\"mytheme\",\"enabled\":true,\"text\":\"#ffffff\"}");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            var host = new FakeHost(ai.pijota.theme.AnsiTheme.dark());
            var manager = new ThemeManager(host);

            var items = manager.discover();

            var names = items.stream().map(ThemeItem::name).toList();
            assertTrue(names.contains("dark"), "expected builtin 'dark', got " + names);
            assertTrue(names.contains("mytheme"), "expected file theme 'mytheme', got " + names);
            assertTrue(items.stream().anyMatch(i -> "dark".equals(i.name()) && i.current()),
                                            "expected current theme to be marked");
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    private static final class FakeHost implements ThemeManager.Host {
        private final java.util.List<String> errors = new java.util.ArrayList<>();
        private final java.util.List<String> statuses = new java.util.ArrayList<>();
        private ai.pijota.theme.AnsiTheme theme;

        FakeHost(ai.pijota.theme.AnsiTheme theme) {
            this.theme = theme;
        }

        @Override
        public ai.pijota.theme.AnsiTheme theme() {
            return theme;
        }

        @Override
        public void setTheme(ai.pijota.theme.AnsiTheme theme) {
            this.theme = theme;
        }

        @Override
        public void appendError(String message) {
            errors.add(message);
        }

        @Override
        public void appendStatus(String message) {
            statuses.add(message);
        }
    }
}
