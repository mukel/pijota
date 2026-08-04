package org.pijota.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single source of truth for pijota's per-user config directory ({@code ~/.pijota}) and the files/subdirectories under
 * it. The default stays stable across platforms to avoid surprising existing users; set {@code -Dpijota.home=...} or
 * {@code PIJOTA_HOME=...} to place it in an OS-native config directory.
 */
public final class PijotaHome {

    private PijotaHome() {}

    /**
     * The config directory itself. Resolved on each call so tests, relaunches, and env/property overrides are honored.
     */
    public static Path dir() {
        String override = System.getProperty("pijota.home");
        if (override == null || override.isBlank())
            override = System.getenv("PIJOTA_HOME");
        return override != null && !override.isBlank()
                                        ? Path.of(override).toAbsolutePath().normalize()
                                        : Path.of(System.getProperty("user.home"), ".pijota");
    }

    /**
     * Creates the config directory owner-only, so nothing under it is readable by other users on the machine.
     *
     * <p>
     * The individual secret files ({@code settings.json}, {@code auth.json}) set their own mode, and writing one
     * happened to tighten the directory too. A user who authenticates by environment variable never writes either, so
     * their home stayed {@code 755} with {@code sessions/} inside it: every transcript, every prompt in
     * {@code history}, and the {@code logs/} directory, all world-readable. The first-run message asks for an API key
     * in the environment, which is exactly the path that never triggered the tightening.
     *
     * <p>
     * Denying traversal on the directory covers the files under it whatever their own mode, which is why this is the
     * one place worth doing it rather than at each of the twenty writers.
     */
    public static Path ensurePrivate() {
        Path dir = dir();
        try {
            Files.createDirectories(dir);
            Util.restrictToOwner(dir);
        } catch (IOException ignored) {
            // Best effort, as everywhere else: an unwritable home is reported by whatever tries to use it, and
            // failing startup over a permission bit would be worse than the exposure it prevents.
        }
        return dir;
    }

    /** A path under the config directory, e.g. {@code resolve("themes", name + ".json")}. */
    public static Path resolve(String first, String... more) {
        Path p = dir().resolve(first);
        for (String segment : more)
            p = p.resolve(segment);
        return p;
    }
}
