package org.pijota.tui;

import java.util.List;

/**
 * Accumulates the lines of a multi-line markdown block (a table, a blockquote) until it can be flushed as one chunk —
 * needed because the stream arrives one line at a time but these blocks parse only with full context.
 */
final class LineBuffer {
    private final StringBuilder sb = new StringBuilder();

    boolean active() {
        return !sb.isEmpty();
    }

    void add(String line) {
        if (!sb.isEmpty())
            sb.append('\n');
        sb.append(line);
    }

    /** Emit the accumulated block (if any) as a single element and reset. */
    void flushInto(List<String> out) {
        if (sb.isEmpty())
            return;
        out.add(sb.toString());
        sb.setLength(0);
    }

    void clear() {
        sb.setLength(0);
    }
}
