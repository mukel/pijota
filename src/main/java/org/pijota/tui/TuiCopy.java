package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.Arrays;
import java.util.stream.Collectors;

final class TuiCopy {
    static final String START_HINT = Keys.label("/help · /model · @ files · Ctrl+P · Shift+Tab");
    // Ordered by what a narrow terminal must keep: footer() drops chips from the end, and the first casualty should
    // be the hint you would have discovered by accident anyway, not the one that says how to get out. At 60 columns
    // this used to lose "Esc cancel".
    /**
     * The two separators the interface is allowed to use, and the rule for choosing between them.
     *
     * <p>
     * {@link #CHIP} joins facts of the same kind -- {@code medium · 4 files · 2s}. {@link #GROUP} opens a different
     * kind -- token count, then what is answering, then what it is doing. Read together, a line says "here are three
     * things, and this one has three parts".
     *
     * <p>
     * The rule was half-applied and nowhere written down, so the widths drifted to one, two and three spaces on
     * neighbouring lines and the two separators looked arbitrary rather than meaningful. Anything wider than
     * {@code GROUP} is column alignment, not a separator, and belongs only where rows line up.
     */
    static final String CHIP = " · ";
    static final String GROUP = "  ";

    static final String SELECTOR_HINT = "↑/↓ navigate · Enter select · Esc cancel · type to search";

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
    static String footer(AnsiTheme theme, int width, String position, String... hints) {
        String chips = Arrays.stream(hints).filter(h -> h != null && !h.isBlank()).map(String::strip)
                                        .collect(Collectors.joining(CHIP));
        String where = position == null ? "" : position.strip();
        if (chips.isEmpty())
            return theme.styled("statusText", GROUP + where);
        // With nothing to count (the empty selector) the hints take the indent themselves, rather than sitting two
        // columns further right than every other footer.
        String left = where.isEmpty() ? "" : theme.styled("statusText", GROUP + where);
        // Drop whole hints the terminal has no room for, rather than letting the row be clipped mid-word. At 60
        // columns "type to search · ↑/↓ navigate · Enter select · Esc cancel" is 63 wide and lost "cancel", which is
        // the one word that tells you how to get out.
        int room = width - AnsiWidth.visibleWidth(where.isEmpty() ? "" : GROUP + where) - GROUP.length();
        return left + theme.styled("dim", GROUP + fit(Keys.label(chips), room));
    }

    /**
     * {@code text} narrowed to {@code room} by dropping whole {@code ·}-separated chips rather than slicing through
     * one. Everything in this interface that has to shrink -- a selector's detail, a footer's hints -- is written as
     * chips, and the separator is where a cut belongs.
     *
     * <p>
     * A plain visual truncation cut wherever the column ran out. At 100 columns every model row read {@code price
     * $0.14/$}, a severed number, which does not look narrow, it looks broken. A shorter row that is entirely true
     * beats a full one that is half a fact.
     *
     * <p>
     * The first chip is the exception: if even it does not fit there is nothing to drop, so it is cut and marked with
     * an ellipsis, which at least says so.
     */
    static String fit(String text, int room) {
        if (text == null || room <= 0)
            return "";
        if (AnsiWidth.visibleWidth(text) <= room)
            return text;
        String[] chips = text.split(CHIP, -1);
        var kept = new StringBuilder(chips[0]);
        for (int i = 1; i < chips.length; i++) {
            String next = kept + CHIP + chips[i];
            if (AnsiWidth.visibleWidth(next) > room)
                break;
            kept.setLength(0);
            kept.append(next);
        }
        return AnsiWidth.visibleWidth(kept.toString()) <= room
                                        ? kept.toString()
                                        : AnsiWidth.truncateVisual(chips[0], Math.max(1, room - 1)) + "…";
    }

}
