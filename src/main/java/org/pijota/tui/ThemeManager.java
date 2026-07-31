package org.pijota.tui;

import org.pijota.theme.AnsiTheme;
import org.pijota.util.PijotaHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ThemeManager {
    interface Host extends TuiHost {
        AnsiTheme theme();

        void setTheme(AnsiTheme theme);
    }

    private final Host host;

    ThemeManager(Host host) {
        this.host = host;
    }

    List<ThemeItem> discover() {
        var items = new ArrayList<ThemeItem>();
        for (String name : builtinNames()) {
            items.add(new ThemeItem(name, "", host.theme().name.equals(name)));
        }
        for (Path p : fileThemes()) {
            String name = themeName(p);
            items.add(new ThemeItem(name, p.toString(), host.theme().name.equals(name)));
        }
        return items;
    }

    void switchTo(String name) {
        try {
            apply(AnsiTheme.loadResource("themes/" + name.toLowerCase() + ".json"), name);
        } catch (IOException e) {
            try {
                Path path = resolveExternal(name);
                if (path != null && Files.isRegularFile(path)) {
                    apply(AnsiTheme.load(path), path.getFileName().toString());
                    return;
                }
            } catch (Exception ignored) {}
            host.appendError("theme not found: " + name);
        }
    }

    void switchToPath(String pathStr) {
        try {
            Path path = Path.of(pathStr);
            if (!path.isAbsolute())
                path = Path.of(System.getProperty("user.dir")).resolve(pathStr);
            if (Files.isRegularFile(path)) {
                var loaded = AnsiTheme.load(path);
                apply(loaded, loaded.name != null ? loaded.name : path.getFileName().toString());
            } else {
                host.appendError("theme not found: " + pathStr);
            }
        } catch (IOException e) {
            host.appendError("theme load failed: " + e.getMessage());
        }
    }

    private void apply(AnsiTheme loaded, String displayName) {
        host.setTheme(loaded);
        host.appendStatus("switched to " + displayName);
    }

    private static Path resolveExternal(String name) {
        Path path = Path.of(name);
        if (!path.isAbsolute()) {
            Path cwd = Path.of(System.getProperty("user.dir"));
            path = cwd.resolve(name);
        }
        if (!Files.isRegularFile(path)) {
            Path pijotaThemes = PijotaHome.resolve("themes", name + ".json");
            if (Files.isRegularFile(pijotaThemes))
                return pijotaThemes;
            Path pijotaTheme = PijotaHome.resolve("theme.json");
            if (Files.isRegularFile(pijotaTheme))
                return pijotaTheme;
        }
        return Files.isRegularFile(path) ? path : null;
    }

    /**
     * The bundled themes ({@code src/main/resources/themes/*.json}). Kept as an explicit list because resource
     * <em>directories</em> can't be enumerated in the native image; GraalVM's {@code resource:} protocol has no
     * filesystem view, and a jar/classpath scan silently returns nothing there, so only the code-fallback dark/light
     * would show. {@code ThemeManagerTest} cross-checks this list against the actual files to catch drift.
     */
    static final List<String> BUILTIN_THEMES = List.of("catppuccin-frappe", "catppuccin-latte", "catppuccin-macchiato",
                                    "catppuccin-mocha", "dark", "gruvbox-dark", "gruvbox-light", "light");

    private static List<String> builtinNames() {
        return BUILTIN_THEMES;
    }

    private static List<Path> fileThemes() {
        var paths = new ArrayList<Path>();
        Path dir = PijotaHome.resolve("themes");
        if (!Files.isDirectory(dir))
            return paths;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(paths::add);
        } catch (IOException ignored) {}
        return paths;
    }

    static String themeName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
