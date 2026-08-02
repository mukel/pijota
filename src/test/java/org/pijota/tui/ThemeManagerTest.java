package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThemeManagerTest {

    @Test
    void themeNameStripsJsonExtension() {
        assertEquals("dark", ThemeManager.themeName(Path.of("dark.json")));
    }

    @Test
    void builtinThemesListMatchesBundledResources() throws Exception {
        // Guards the native-image fix: BUILTIN_THEMES is an explicit list (resource dirs can't be enumerated in the
        // native image), so it must stay in sync with the actual themes/*.json, checked here where enumeration works.
        var dir = ThemeManager.class.getClassLoader().getResource("themes");
        assertNotNull(dir, "bundled themes/ resource directory");
        try (var files = Files.list(Path.of(dir.toURI()))) {
            var actual = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                                            .map(n -> n.substring(0, n.length() - ".json".length())).sorted().toList();
            assertEquals(actual, ThemeManager.BUILTIN_THEMES,
                                            "BUILTIN_THEMES drifted from src/main/resources/themes/*.json. Update it");
        }
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
        var host = new FakeHost(AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchTo("gruvbox-dark");

        assertEquals("gruvbox-dark", host.theme.name);
        assertTrue(host.statuses.stream().anyMatch(s -> s.contains("switched")),
                                        "expected status message about switching theme, got " + host.statuses);
    }

    @Test
    void switchToMissingBuiltinReportsError() {
        var host = new FakeHost(AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchTo("definitely-missing-theme");

        assertTrue(host.errors.stream().anyMatch(e -> e.contains("theme not found")),
                                        "expected error message about missing theme, got " + host.errors);
    }

    @Test
    void switchToPathAbsolute(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("custom.json");
        Files.writeString(file, "{\"name\":\"custom\",\"enabled\":true,\"text\":\"#ffffff\"}");

        var host = new FakeHost(AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchToPath(file.toString());

        assertEquals("custom", host.theme.name);
        assertTrue(host.statuses.stream().anyMatch(s -> s.contains("switched")),
                                        "expected status message about switching theme, got " + host.statuses);
    }

    @Test
    void switchToPathMissingReportsError() {
        var host = new FakeHost(AnsiTheme.dark());
        var manager = new ThemeManager(host);

        manager.switchToPath("/nonexistent/theme/file.json");

        assertTrue(host.errors.stream().anyMatch(e -> e.contains("theme not found")),
                                        "expected error message about missing theme path, got " + host.errors);
    }

    @Test
    void switchToPathInvalidThemeReportsLoadError(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("bad.json");
        Files.writeString(file, "not json");

        var host = new FakeHost(AnsiTheme.dark());
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
            var host = new FakeHost(AnsiTheme.loadResource("themes/gruvbox-dark.json"));
            var manager = new ThemeManager(host);

            var items = manager.discover();

            var names = items.stream().map(ThemeItem::name).toList();
            assertTrue(names.contains("gruvbox-dark"), "expected builtin 'gruvbox-dark', got " + names);
            assertTrue(names.contains("mytheme"), "expected file theme 'mytheme', got " + names);
            assertTrue(items.stream().anyMatch(i -> "gruvbox-dark".equals(i.name()) && i.current()),
                                            "expected current theme to be marked");
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void switchToExternalThemeInPijotaHome(@TempDir Path tmp) throws Exception {
        var themesDir = tmp.resolve(".pijota").resolve("themes");
        Files.createDirectories(themesDir);
        Files.writeString(themesDir.resolve("custom.json"),
                                        "{\"name\":\"custom\",\"enabled\":true,\"text\":\"#ffffff\"}");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            var host = new FakeHost(AnsiTheme.dark());
            var manager = new ThemeManager(host);
            manager.switchTo("custom");
            assertEquals("custom", host.theme.name);
            assertTrue(host.statuses.stream().anyMatch(s -> s.contains("switched")),
                                            "expected status message about switching theme, got " + host.statuses);
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void switchToPathRelativeResolvesAgainstUserDir(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("rel.json");
        Files.writeString(file, "{\"name\":\"rel\",\"enabled\":true,\"text\":\"#ffffff\"}");

        String previousDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tmp.toString());
        try {
            var host = new FakeHost(AnsiTheme.dark());
            var manager = new ThemeManager(host);
            manager.switchToPath("rel.json");
            assertEquals("rel", host.theme.name);
        } finally {
            System.setProperty("user.dir", previousDir);
        }
    }

    @Test
    void discoverWithoutFileThemesListsBuiltinsOnly(@TempDir Path tmp) {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            var host = new FakeHost(AnsiTheme.dark());
            var manager = new ThemeManager(host);
            var items = manager.discover();
            var names = items.stream().map(ThemeItem::name).toList();
            assertTrue(names.contains("light"));
            assertTrue(names.contains("gruvbox-dark"));
            assertFalse(names.contains("custom"));
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    private static final class FakeHost implements ThemeManager.Host {
        private final List<String> errors = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private AnsiTheme theme;

        FakeHost(AnsiTheme theme) {
            this.theme = theme;
        }

        @Override
        public AnsiTheme theme() {
            return theme;
        }

        @Override
        public void setTheme(AnsiTheme theme) {
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
