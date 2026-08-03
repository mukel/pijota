package org.pijota;

import org.pijota.cli.ArgParser;
import org.pijota.cli.Args;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThemeLoaderTest {

    private static Args emptyArgs() {
        return ArgParser.parse(new String[] {});
    }

    private static SettingsManager settingsWithTheme(String theme) {
        return new SettingsManager(new AppConfig(null, null, null, null, null, null, null, null, theme, null, null,
                                        true, false, false, null, null, new AppConfig.CompactionConfig(), null, null,
                                        List.of(), Map.of(), null), ignored -> {});
    }

    private static SettingsManager emptySettings() {
        return new SettingsManager(new AppConfig(), ignored -> {});
    }

    @Test
    void loadsDefaultBuiltinWhenUnset() {
        var theme = ThemeLoader.load(emptySettings(), emptyArgs(), "/tmp");
        assertEquals("gruvbox-dark", theme.name);
    }

    @Test
    void loadsLightBuiltin() {
        var theme = ThemeLoader.load(emptySettings(), ArgParser.parse(new String[] {"--theme", "light"}), "/tmp");
        assertEquals("light", theme.name);
    }

    @Test
    void settingsThemeIsUsedWhenNoArgs() {
        var theme = ThemeLoader.load(settingsWithTheme("light"), emptyArgs(), "/tmp");
        assertEquals("light", theme.name);
    }

    @Test
    void argsThemeOverridesSettingsTheme() {
        var theme = ThemeLoader.load(settingsWithTheme("light"), ArgParser.parse(new String[] {"--theme", "dark"}),
                                        "/tmp");
        assertEquals("dark", theme.name);
    }

    @Test
    void loadsCustomThemeFromPath() throws Exception {
        Path dir = Files.createTempDirectory("theme-test");
        Path themeFile = dir.resolve("custom.json");
        Files.writeString(themeFile, "{\"name\":\"custom\",\"text\":\"#ff0000\"}");

        var theme = ThemeLoader.load(emptySettings(), ArgParser.parse(new String[] {"--theme", themeFile.toString()}),
                                        "/tmp");

        assertEquals("custom", theme.name);
        assertEquals("#ff0000", theme.text);
    }

    @Test
    void loadsThemeFromDirectory() throws Exception {
        Path dir = Files.createTempDirectory("theme-test");
        Files.writeString(dir.resolve("ocean.json"), "{\"name\":\"ocean\",\"text\":\"#0000ff\"}");

        var settings = new SettingsManager(new AppConfig(null, null, null, null, null, null, null, null, "ocean",
                                        List.of(dir.toString()), null, true, false, false, null, null,
                                        new AppConfig.CompactionConfig(), null, null, List.of(), Map.of(), null),
                                        ignored -> {});

        var theme = ThemeLoader.load(settings, emptyArgs(), "/tmp");
        assertEquals("ocean", theme.name);
        assertEquals("#0000ff", theme.text);
    }

    @Test
    void fallsBackToDarkForMissingTheme() {
        var theme = ThemeLoader.load(emptySettings(), ArgParser.parse(new String[] {"--theme", "definitely-missing"}),
                                        "/tmp");
        assertEquals("dark", theme.name);
    }

    @Test
    void fallsBackToDarkForInvalidThemeFile() throws Exception {
        Path dir = Files.createTempDirectory("theme-test");
        Path badFile = dir.resolve("broken.json");
        Files.writeString(badFile, "not json");

        var theme = ThemeLoader.load(emptySettings(), ArgParser.parse(new String[] {"--theme", badFile.toString()}),
                                        "/tmp");
        assertEquals("dark", theme.name);
    }

    @Test
    void noThemesSkipsDiscovery() throws Exception {
        Path dir = Files.createTempDirectory("theme-test");
        Files.writeString(dir.resolve("ocean.json"), "{\"name\":\"ocean\",\"text\":\"#0000ff\"}");

        var settings = new SettingsManager(new AppConfig(null, null, null, null, null, null, null, null, null,
                                        List.of(dir.toString()), null, true, false, false, null, null,
                                        new AppConfig.CompactionConfig(), null, null, List.of(), Map.of(), null),
                                        ignored -> {});

        var theme = ThemeLoader.load(settings, ArgParser.parse(new String[] {"--no-themes"}), "/tmp");
        assertEquals("gruvbox-dark", theme.name); // built-in default still loads; only user-dir discovery is skipped
    }

    /**
     * A file the user wrote wins over a name pijota ships. The bundled lookup used to run first, so a
     * {@code ~/.pijota/themes/dark.json} could never be selected by name -- verified at the time with an all-magenta
     * dark.json that the binary ignored entirely -- while the picker listed it on its own row, which did nothing.
     */
    @Test
    void aUserThemeOverridesABundledNameOfTheSameName() throws Exception {
        Path dir = Files.createTempDirectory("theme-override");
        Files.writeString(dir.resolve("dark.json"),
                                        "{\"name\":\"dark\",\"vars\":{\"x\":\"#ff00ff\"},\"colors\":{\"text\":\"x\"}}");

        var settings = new SettingsManager(new AppConfig(null, null, null, null, null, null, null, null, "dark",
                                        List.of(dir.toString()), null, true, false, false, null, null,
                                        new AppConfig.CompactionConfig(), null, null, List.of(), Map.of(), null),
                                        ignored -> {});
        var theme = ThemeLoader.load(settings, emptyArgs(), "/tmp");

        assertEquals("dark", theme.name);
        assertTrue(theme.text.contains("ff00ff"), "the user's file, not the bundled dark: " + theme.text);
    }

    /** A bundled name with no user file of that name still resolves, which is the ordinary case. */
    @Test
    void aBundledNameStillLoadsWhenNothingShadowsIt() {
        var theme = ThemeLoader.load(settingsWithTheme("gruvbox-light"), emptyArgs(), "/tmp");

        assertEquals("gruvbox-light", theme.name);
    }

}
