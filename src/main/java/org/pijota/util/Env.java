package org.pijota.util;

/**
 * Resolves pijota's own configuration that may come from the environment. Convention: every {@code PIJOTA_*}
 * environment variable has a matching {@code -Dpijota.*} system property that <b>overrides</b> it (the property wins),
 * so the same setting can be passed either way, e.g. {@code PIJOTA_GECKODRIVER} ⟷ {@code -Dpijota.browse.geckodriver}.
 * Blank values count as unset. (External/standard variables such as API keys, {@code NO_COLOR}, {@code EDITOR},
 * {@code SHELL}, and {@code PATH} are not pijota's to remap and are read directly.)
 */
public final class Env {
    private Env() {}

    /** The {@code -D<sysProp>} system property if set, else the {@code <envVar>} environment variable, else null. */
    public static String get(String sysProp, String envVar) {
        String prop = System.getProperty(sysProp);
        if (prop != null && !prop.isBlank())
            return prop;
        String env = System.getenv(envVar);
        return env != null && !env.isBlank() ? env : null;
    }

    /**
     * A positive whole-seconds knob (via {@link #get}); falls back to {@code fallback} when unset, non-numeric, or
     * non-positive.
     */
    public static long seconds(String sysProp, String envVar, long fallback) {
        String value = get(sysProp, envVar);
        if (value == null)
            return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
