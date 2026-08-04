package org.pijota.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Which words changed between two versions of one line, for the intra-line emphasis a diff paints on top of its
 * add/delete rows.
 *
 * <p>
 * Pure and terminal-free: offsets in, offsets out. Nothing here knows about ANSI, themes or terminals, so the same
 * answer serves the TUI, an HTML export or a web view.
 *
 * <p>
 * The hard part is not finding the changed words, it is declining to. Two lines that share almost nothing produce a
 * highlight over most of their length, which is confetti: strictly worse than the plain line diff it replaced, because
 * it claims to point somewhere and points everywhere. {@link #spans} answers empty whenever the detail would not be
 * worth reading, and the caller renders the row exactly as it would have.
 */
public final class WordDiff {

    /**
     * How much of the line has to survive for word-level detail to mean anything.
     *
     * <p>
     * Below this the pair is a rewrite that happens to share a few tokens, and pointing at the survivors is noise.
     * Measured over the hunks in this repository's own history: at 0.4 the emphasis stays a minority of the line in the
     * overwhelming majority of real edits, which is the property that makes it readable at a glance.
     */
    private static final double MIN_UNCHANGED_RATIO = 0.4;

    /**
     * Myers is O(nd), and this runs on the interactive draw path for every rendered row. A minified or generated line
     * is not worth stalling a redraw for, and word detail on one is unreadable anyway.
     */
    private static final int MAX_TOKENS = 400;

    private WordDiff() {}

    /** A half-open character range of one line, in that line's own coordinates. */
    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start)
                throw new IllegalArgumentException("bad span " + start + ".." + end);
        }
    }

    /** The changed ranges of each side, or two empty lists when word detail would not be worth showing. */
    public record Result(List<Span> oldSpans, List<Span> newSpans) {
        public Result {
            oldSpans = List.copyOf(oldSpans);
            newSpans = List.copyOf(newSpans);
        }

        public boolean isEmpty() {
            return oldSpans.isEmpty() && newSpans.isEmpty();
        }

        static Result none() {
            return new Result(List.of(), List.of());
        }
    }

    /**
     * Splits into the units a reader compares: identifier-ish runs, single punctuation characters, and runs of
     * whitespace.
     *
     * <p>
     * Whitespace is a token rather than a separator so that a change in indentation is a change like any other. It is a
     * real edit, and a diff that silently declined to show it would be lying about what happened to the file.
     */
    static List<String> tokenize(String line) {
        var tokens = new ArrayList<String>();
        int i = 0;
        while (i < line.length()) {
            int start = i;
            int cp = line.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                while (i < line.length() && Character.isWhitespace(line.codePointAt(i)))
                    i += Character.charCount(line.codePointAt(i));
            } else if (isWordChar(cp)) {
                while (i < line.length() && isWordChar(line.codePointAt(i)))
                    i += Character.charCount(line.codePointAt(i));
            } else {
                i += Character.charCount(cp); // punctuation stands alone: `a.b` is three tokens, not one
            }
            tokens.add(line.substring(start, i));
        }
        return tokens;
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    /** The changed ranges of {@code oldLine} and {@code newLine}, or empty when the detail is not worth showing. */
    public static Result spans(String oldLine, String newLine) {
        if (oldLine == null || newLine == null || oldLine.equals(newLine))
            return Result.none();
        var oldTokens = tokenize(oldLine);
        var newTokens = tokenize(newLine);
        if (oldTokens.size() > MAX_TOKENS || newTokens.size() > MAX_TOKENS)
            return Result.none();

        var rows = DiffAlgorithm.diff(oldTokens.toArray(String[]::new), newTokens.toArray(String[]::new));

        int unchanged = 0;
        for (var row : rows)
            if (row.kind() == DiffAlgorithm.Kind.CONTEXT)
                unchanged++;
        // Against the longer side: a short line rewritten into a long one shares little of what the reader now sees.
        int longest = Math.max(oldTokens.size(), newTokens.size());
        if (longest == 0 || (double) unchanged / longest < MIN_UNCHANGED_RATIO)
            return Result.none();

        var oldSpans = new ArrayList<Span>();
        var newSpans = new ArrayList<Span>();
        int oldAt = 0;
        int newAt = 0;
        for (var row : rows) {
            int length = row.text().length();
            switch (row.kind()) {
                case CONTEXT -> {
                    oldAt += length;
                    newAt += length;
                }
                case DELETE -> {
                    append(oldSpans, oldAt, oldAt + length);
                    oldAt += length;
                }
                case ADD -> {
                    append(newSpans, newAt, newAt + length);
                    newAt += length;
                }
            }
        }
        return new Result(oldSpans, newSpans);
    }

    /** Adjacent changed tokens are one region: three highlights with no gap read as one anyway, and cost three. */
    private static void append(List<Span> spans, int start, int end) {
        if (!spans.isEmpty() && spans.get(spans.size() - 1).end() == start) {
            Span last = spans.remove(spans.size() - 1);
            spans.add(new Span(last.start(), end));
            return;
        }
        spans.add(new Span(start, end));
    }

    /** Whether {@code offset} falls in any of {@code spans}; they are ordered and disjoint, so a scan is enough. */
    public static boolean covers(List<Span> spans, int offset) {
        for (Span span : spans) {
            if (offset < span.start())
                return false;
            if (offset < span.end())
                return true;
        }
        return false;
    }
}
