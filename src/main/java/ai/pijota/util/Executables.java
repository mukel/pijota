package ai.pijota.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-OS detection of executables on {@code PATH} — like {@code which}/{@code where}, without spawning a process.
 * Lets features probe for optional external tools (rg, fzf, fd, tmux, a browser/driver, …) and degrade gracefully when
 * they are absent. On Windows an extensionless name is resolved through the {@code PATHEXT} suffixes
 * ({@code .EXE}/{@code .BAT}/{@code .CMD}/…); elsewhere the name is used verbatim.
 */
public final class Executables {
    private Executables() {}

    /** Whether {@code name} resolves to an executable on {@code PATH} (or is itself an executable file path). */
    public static boolean isAvailable(String name) {
        return find(name) != null;
    }

    /** Whether any of {@code names} resolves to an executable — for tools whose binary name differs by OS. */
    public static boolean anyAvailable(List<String> names) {
        return findAny(names) != null;
    }

    /**
     * The path {@code name} resolves to on {@code PATH}, or {@code null} if not found. A {@code name} that is already a
     * path (contains a separator) is returned as-is when it points at an executable file.
     */
    public static Path find(String name) {
        if (name == null || name.isBlank())
            return null;
        if (name.indexOf('/') >= 0 || name.indexOf(File.separatorChar) >= 0)
            return executableFile(Path.of(name));
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank())
            return null;
        List<String> candidates = nameCandidates(name);
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank())
                continue;
            for (String candidate : candidates) {
                Path resolved = executableFile(Path.of(dir, candidate));
                if (resolved != null)
                    return resolved;
            }
        }
        return null;
    }

    /** The first of {@code names} found on {@code PATH}, or {@code null} — for OS-specific binaries (e.g. browsers). */
    public static Path findAny(List<String> names) {
        for (String n : names) {
            Path p = find(n);
            if (p != null)
                return p;
        }
        return null;
    }

    private static Path executableFile(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p) ? p : null;
    }

    private static List<String> nameCandidates(String name) {
        if (!Platform.isWindows() || name.contains("."))
            return List.of(name);
        var out = new ArrayList<String>();
        out.add(name); // an explicit extension may already be present, or it's a script with no extension
        String pathext = System.getenv("PATHEXT");
        for (String ext : (pathext != null && !pathext.isBlank() ? pathext : ".EXE;.BAT;.CMD;.COM").split(";"))
            if (!ext.isBlank())
                out.add(name + ext.toLowerCase());
        return out;
    }
}
