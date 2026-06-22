package ai.pijota;

import static org.junit.jupiter.api.Assertions.*;

import ai.pijota.cli.Args;
import ai.pijota.cli.ArgParser;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ThemeLoaderTest {

    private static Args emptyArgs() {
        return ArgParser.parse(new String[] {});
    }

    private static SettingsManager settingsWithTheme(String theme) {
        return new SettingsManager(new AppConfig(null, null, null, null, null, null, null, null, theme, null, null,
                                        true, false, false, null, false, new AppConfig.CompactionConfig(), null),
                                        _ -> {});
    }

    private static SettingsManager emptySettings() {
        return new SettingsManager(new AppConfig(), _ -> {});
    }

    @Test
    void loadsDarkBuiltinByDefault() {
        var theme = ThemeLoader.load(emptySettings(), emptyArgs(), "/tmp");
        assertEquals("dark", theme.name);
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
                                        List.of(dir.toString()), null, true, false, false, null, false,
                                        new AppConfig.CompactionConfig(), null), _ -> {});

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
                                        List.of(dir.toString()), null, true, false, false, null, false,
                                        new AppConfig.CompactionConfig(), null), _ -> {});

        var theme = ThemeLoader.load(settings, ArgParser.parse(new String[] {"--no-themes"}), "/tmp");
        assertEquals("dark", theme.name);
    }
}
