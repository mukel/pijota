package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ThemeManager {
    private final AppendOnlyTui tui;

    ThemeManager(AppendOnlyTui tui) { this.tui = tui; }

    List<ThemeItem> discover() {
        var items = new ArrayList<ThemeItem>();
        for (String name : builtinNames()) {
            items.add(new ThemeItem(name, "", tui.theme().name.equals(name)));
        }
        for (Path p : fileThemes()) {
            String name = themeName(p);
            items.add(new ThemeItem(name, p.toString(), tui.theme().name.equals(name)));
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
            tui.appendStatusError("theme not found: " + name);
        }
    }

    void switchToPath(String pathStr) {
        try {
            Path path = Path.of(pathStr);
            if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(pathStr);
            if (Files.isRegularFile(path)) {
                var loaded = AnsiTheme.load(path);
                apply(loaded, loaded.name != null ? loaded.name : path.getFileName().toString());
            } else {
                tui.appendStatusError("theme not found: " + pathStr);
            }
        } catch (IOException e) {
            tui.appendStatusError("theme load failed: " + e.getMessage());
        }
    }

    private void apply(AnsiTheme loaded, String displayName) {
        tui.setTheme(loaded);
        tui.appendStatus("switched to " + displayName);
    }

    private static Path resolveExternal(String name) {
        Path path = Path.of(name);
        if (!path.isAbsolute()) {
            Path cwd = Path.of(System.getProperty("user.dir"));
            path = cwd.resolve(name);
        }
        if (!Files.isRegularFile(path)) {
            Path pijotaThemes = Path.of(System.getProperty("user.home"), ".pijota", "themes", name + ".json");
            if (Files.isRegularFile(pijotaThemes)) return pijotaThemes;
            Path pijotaTheme = Path.of(System.getProperty("user.home"), ".pijota", "theme.json");
            if (Files.isRegularFile(pijotaTheme)) return pijotaTheme;
        }
        return Files.isRegularFile(path) ? path : null;
    }

    private static List<String> builtinNames() {
        var names = new ArrayList<String>();
        try {
            java.net.URL dir = AnsiTheme.class.getClassLoader().getResource("themes");
            if (dir != null && "jar".equals(dir.getProtocol())) {
                String path = dir.getPath();
                String jarPath = path.substring(5, path.indexOf('!'));
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                    jar.stream()
                        .filter(e -> e.getName().startsWith("themes/") && e.getName().endsWith(".json"))
                        .map(e -> e.getName().substring(7, e.getName().length() - 5))
                        .sorted()
                        .forEach(names::add);
                }
            } else if (dir != null) {
                try (var stream = Files.list(Path.of(dir.toURI()))) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString().replace(".json", ""))
                        .sorted()
                        .forEach(names::add);
                }
            }
        } catch (Exception ignored) {}
        return names;
    }

    private static List<Path> fileThemes() {
        var paths = new ArrayList<Path>();
        Path dir = Path.of(System.getProperty("user.home"), ".pijota", "themes");
        if (!Files.isDirectory(dir)) return paths;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted().forEach(paths::add);
        } catch (IOException ignored) {}
        return paths;
    }

    private static String themeName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
