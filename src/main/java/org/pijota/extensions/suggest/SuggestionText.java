package org.pijota.extensions.suggest;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a model's reply is usable as a suggested next message. Pure, and deliberately strict: a suggestion is
 * unsolicited, so the cost of a bad one is higher than the cost of showing nothing.
 *
 * <p>
 * Single-line is a rejection rather than a repair. A stray newline in a rendered prompt row advances a line the layout
 * never counted, which puts the frame's row bookkeeping out by one and eats a row of transcript on the next redraw --
 * so a multi-line candidate is dropped, not flattened.
 */
public final class SuggestionText {
    private SuggestionText() {}

    /** Long enough to be a real instruction, short enough to read without moving your eyes. */
    static final int MAX_WORDS = 12;

    /** Openings that mean the model answered as itself instead of predicting the user. */
    private static final Set<String> AGENT_VOICE = Set.of("i'll", "i will", "let me", "sure", "certainly", "here's",
                                    "here is", "okay", "ok,", "i can", "i've", "i have", "would you", "shall i");

    /**
     * The suggestion to show, or empty when the reply cannot be used. Empty is a normal outcome: the prompt tells the
     * model to say nothing when the next step is not obvious.
     */
    public static Optional<String> validate(String raw) {
        if (raw == null)
            return Optional.empty();
        String text = raw.strip();
        if (text.isEmpty() || text.lines().count() > 1)
            return Optional.empty();

        text = unwrapQuotes(text);
        if (text.isEmpty())
            return Optional.empty();

        // A question is the model asking the user something, which is the opposite of predicting what they will type.
        if (text.endsWith("?"))
            return Optional.empty();

        String lower = text.toLowerCase(Locale.ROOT);
        if (AGENT_VOICE.stream().anyMatch(lower::startsWith))
            return Optional.empty();

        if (text.split("\\s+").length > MAX_WORDS)
            return Optional.empty();

        // Two sentences is an explanation, not something a user types into a prompt.
        if (text.replaceAll("[^.!]", "").length() > 1)
            return Optional.empty();

        return Optional.of(stripTrailingPeriod(text));
    }

    /** Models like to quote the thing they were asked to produce. */
    private static String unwrapQuotes(String text) {
        String t = text;
        while (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'")
                                        || t.startsWith("`") && t.endsWith("`")))
            t = t.substring(1, t.length() - 1).strip();
        return t;
    }

    /** Nobody types a full stop at the end of a prompt. */
    private static String stripTrailingPeriod(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1).stripTrailing() : text;
    }
}
