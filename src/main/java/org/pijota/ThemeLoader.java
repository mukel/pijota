package org.pijota;

import org.pijota.cli.Args;
import org.pijota.config.SettingsManager;
import org.pijota.theme.AnsiTheme;
import org.pijota.util.PijotaHome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ThemeLoader {
    private static final Logger logger = LoggerFactory.getLogger(ThemeLoader.class);

    /**
     * Default theme when none is configured, and the dark side of {@code --theme auto}. Gruvbox rather than the
     * generic editor palette every terminal tool defaults to: pijota should not open looking like something else.
     */
    private static final String DEFAULT_THEME = "gruvbox-dark";
    /** The light side of {@code --theme auto}; the same family, so the pair reads as one choice. */
    private static final String AUTO_LIGHT_THEME = "gruvbox-light";

    private ThemeLoader() {}

    static AnsiTheme load(SettingsManager settings, Args args, String cwd) {
        AnsiTheme theme = AnsiTheme.dark();
        try {
            theme = resolve(settings, args, cwd);
        } catch (Exception e) {
            logger.warn("Theme load failed: {}", e.getMessage());
        }
        if (System.getenv("NO_COLOR") != null) {
            theme.enabled = false;
        }
        return theme;
    }

    private static AnsiTheme resolve(SettingsManager settings, Args args, String cwd) throws Exception {
        var candidates = new LinkedHashMap<String, Path>();
        if (!args.noThemes()) {
            addThemeDir(candidates, PijotaHome.resolve("themes"));
            for (String ref : safeList(settings.config().themes()))
                addThemeRef(candidates, Path.of(cwd), ref);
        }
        for (String ref : args.themePaths())
            addThemeRef(candidates, Path.of(cwd), ref);

        String selected = !args.themePaths().isEmpty() ? args.themePaths().getLast() : settings.config().theme();
        if (selected == null || selected.isBlank())
            selected = DEFAULT_THEME;
        if ("auto".equalsIgnoreCase(selected)) // pick a light/dark theme from the terminal background
            selected = TerminalBackground.detect() == TerminalBackground.Mode.LIGHT ? AUTO_LIGHT_THEME : DEFAULT_THEME;

        AnsiTheme builtin = loadBuiltin(selected);
        if (builtin != null)
            return builtin;

        Path selectedPath = resolveThemePath(Path.of(cwd), selected);
        if (selectedPath != null && Files.isRegularFile(selectedPath))
            return AnsiTheme.load(selectedPath);

        Path byName = candidates.get(selected);
        if (byName == null && selected.endsWith(".json")) {
            byName = candidates.get(selected.substring(0, selected.length() - 5));
        }
        if (byName != null)
            return AnsiTheme.load(byName);

        Path legacy = PijotaHome.resolve("theme.json");
        if (Files.isRegularFile(legacy))
            return AnsiTheme.load(legacy);
        return AnsiTheme.dark();
    }

    private static AnsiTheme loadBuiltin(String name) {
        if (name == null || name.isBlank())
            return null;
        String normalized = name.toLowerCase().replace('_', '-');
        try {
            return AnsiTheme.loadResource("themes/" + normalized + ".json");
        } catch (Exception ignored) {
            return switch (normalized) {
                case "dark" -> AnsiTheme.dark();
                case "light" -> AnsiTheme.light();
                default -> null;
            };
        }
    }

    private static void addThemeRef(Map<String, Path> out, Path base, String ref) {
        if (ref == null || ref.isBlank())
            return;
        Path path = resolveThemePath(base, ref);
        if (path == null)
            return;
        if (Files.isDirectory(path))
            addThemeDir(out, path);
        else if (Files.isRegularFile(path))
            addThemeFile(out, path);
    }

    private static Path resolveThemePath(Path base, String ref) {
        Path path = Path.of(ref);
        return path.isAbsolute() ? path : base.resolve(path).normalize();
    }

    private static void addThemeDir(Map<String, Path> out, Path dir) {
        if (!Files.isDirectory(dir))
            return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> addThemeFile(out, p));
        } catch (Exception ignored) {}
    }

    private static void addThemeFile(Map<String, Path> out, Path path) {
        String file = path.getFileName().toString();
        String name = file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;
        out.putIfAbsent(name, path);
        try {
            AnsiTheme loaded = AnsiTheme.load(path);
            if (loaded.name != null && !loaded.name.isBlank())
                out.putIfAbsent(loaded.name, path);
        } catch (Exception ignored) {}
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
