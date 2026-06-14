package ai.pijota.util;

import java.nio.file.Path;

/**
 * Single source of truth for pijota's per-user config directory ({@code ~/.pijota}) and the files/subdirectories under
 * it. Keeping the {@code .pijota} convention in one place means a rename (or an env override later) touches only this
 * class instead of a dozen scattered {@code Path.of(System.getProperty("user.home"), ".pijota", …)} calls.
 */
public final class PijotaHome {

    private PijotaHome() {}

    /**
     * The {@code ~/.pijota} directory itself. Resolved from {@code user.home} on each call (not cached) so a test or
     * relaunch that overrides the home directory is honored.
     */
    public static Path dir() {
        return Path.of(System.getProperty("user.home"), ".pijota");
    }

    /** A path under {@code ~/.pijota}, e.g. {@code resolve("themes", name + ".json")}. */
    public static Path resolve(String first, String... more) {
        Path p = dir().resolve(first);
        for (String segment : more)
            p = p.resolve(segment);
        return p;
    }
}
