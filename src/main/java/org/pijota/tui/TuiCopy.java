package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.Arrays;
import java.util.stream.Collectors;

final class TuiCopy {
    static final String START_HINT = Keys.label("/help · /model · @ files · Ctrl+P · Shift+Tab");
    static final String SELECTOR_HINT = "type to search · ↑/↓ navigate · Enter select · Esc cancel";

    private TuiCopy() {}

    /**
     * A region's footer: where you are, then what you can press. One grammar for all of them. The selector, the
     * completions popup and the log viewer each had their own, two joining the hints with {@code "  · "} and one with
     * plain {@code "  "}, so the same line read differently depending on which overlay was open.
     *
     * <p>
     * It is deliberately the same shape as a {@link Report} header: subject, two spaces, dim chips joined by {@code ·}.
     * Headers and footers then follow one rule instead of two conventions that drift apart.
     */
    static String footer(AnsiTheme theme, String position, String... hints) {
        String chips = Arrays.stream(hints).filter(h -> h != null && !h.isBlank()).map(String::strip)
                                        .collect(Collectors.joining(" · "));
        String where = position == null ? "" : position.strip();
        if (chips.isEmpty())
            return theme.styled("statusText", "  " + where);
        // With nothing to count (the empty selector) the hints take the indent themselves, rather than sitting two
        // columns further right than every other footer.
        String left = where.isEmpty() ? "" : theme.styled("statusText", "  " + where);
        return left + theme.styled("dim", "  " + Keys.label(chips));
    }
}
