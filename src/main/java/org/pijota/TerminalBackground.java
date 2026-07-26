package org.pijota;

/**
 * Best-effort detection of the terminal's background brightness, for the {@code theme: "auto"} / {@code --theme auto}
 * setting. Reads the {@code COLORFGBG} environment variable (exported by konsole, urxvt, and terminals/users that opt
 * in) — a safe, synchronous signal that needs no terminal round-trip, so it can't hang or corrupt the terminal at
 * startup. When {@code COLORFGBG} is absent or unparseable the mode is {@link Mode#UNKNOWN} and the caller falls back
 * to the dark theme.
 */
final class TerminalBackground {

    enum Mode {
        LIGHT, DARK, UNKNOWN
    }

    private TerminalBackground() {}

    static Mode detect() {
        return fromColorFgBg(System.getenv("COLORFGBG"));
    }

    /**
     * Parses {@code COLORFGBG} ({@code "foreground;background"}, or {@code "fg;default;bg"}) whose fields are ANSI
     * palette indices. The background is the last field: indices 0–6 and 8 are dark backgrounds; 7 and 9–15 are light
     * (matching the heuristic Vim uses). A missing, {@code "default"}, or non-numeric background yields
     * {@link Mode#UNKNOWN}.
     */
    static Mode fromColorFgBg(String value) {
        if (value == null || value.isBlank())
            return Mode.UNKNOWN;
        String[] fields = value.trim().split(";");
        try {
            int bg = Integer.parseInt(fields[fields.length - 1].trim());
            return (bg == 7 || bg >= 9) ? Mode.LIGHT : Mode.DARK;
        } catch (NumberFormatException e) {
            return Mode.UNKNOWN;
        }
    }
}
