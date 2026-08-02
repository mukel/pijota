package org.pijota.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How file paths are rendered in tool traces.
 *
 * <ul>
 * <li>{@link #FULL}: the absolute path.
 * <li>{@link #RELATIVE}: relative to the cwd (or the absolute/{@code ~} path when outside it).
 * <li>{@link #COMPACT}: like {@link #RELATIVE} but each intermediate directory shrinks to its first letter
 * ({@code s/m/j/util/File.java}).
 * <li>{@link #NAME}: the filename only ({@code File.java}).
 * </ul>
 */
public enum PathDisplay {
    FULL, RELATIVE, COMPACT, NAME;

    private static final PathDisplay[] ALL = values(); // values() copies each call; cache it for next()

    /** Parse a settings value (case-insensitive); unknown/blank falls back to {@link #COMPACT}. */
    public static PathDisplay fromConfig(String value) {
        if (value != null && !value.isBlank())
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        return COMPACT;
    }

    /** The settings strings a user may write, so a message about them cannot list a value that is not one. */
    public static List<String> names() {
        return Arrays.stream(ALL).map(PathDisplay::config).toList();
    }

    /** Whether {@code value} names a mode. {@link #fromConfig} falls back for a typo, which a validator must not. */
    public static boolean isKnown(String value) {
        return value != null && names().contains(value.trim().toLowerCase(Locale.ROOT));
    }

    /** The settings string for this mode (lowercase enum name). */
    public String config() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Next mode in declaration order, wrapping, used by the cycle toggle. */
    public PathDisplay next() {
        return ALL[(ordinal() + 1) % ALL.length];
    }
}
