package ai.pijota.util;

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

    /** A path under the config directory, e.g. {@code resolve("themes", name + ".json")}. */
    public static Path resolve(String first, String... more) {
        Path p = dir().resolve(first);
        for (String segment : more)
            p = p.resolve(segment);
        return p;
    }
}
