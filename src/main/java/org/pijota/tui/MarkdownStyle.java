package org.pijota.tui;

/** Which terminal markdown renderer the TUI uses: {@code PLAIN} (barebones, delimiters dimmed) or {@code RICH}. */
public enum MarkdownStyle {
    PLAIN, RICH;

    /** Parse a settings value; anything other than "plain" (case-insensitive) selects the rich renderer. */
    public static MarkdownStyle from(String value) {
        return value != null && value.strip().equalsIgnoreCase("plain") ? PLAIN : RICH;
    }
}
