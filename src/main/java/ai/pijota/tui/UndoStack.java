package ai.pijota.tui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Per-prompt undo/redo of the text buffer. A {@link Snapshot} captures the whole buffer (text + cursor), which keeps
 * restore trivially correct for multi-line edits. Consecutive same-{@link Kind} edits coalesce into one step so undo
 * removes a typed run (or a held-backspace run) at once rather than one character at a time; a cursor move or any
 * {@code OTHER} edit ends the run via {@link #breakRun()}.
 */
final class UndoStack {

    enum Kind {
        TYPING, DELETING, OTHER
    }

    record Snapshot(String text, int row, int col) {
    }

    private static final int MAX = 200;

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private Kind runKind; // kind of the current coalescing run; null means the next edit starts a fresh step

    /**
     * Whether an edit of this {@code kind} would start a fresh undo step rather than coalesce into the current run.
     * Lets the caller skip capturing a pre-edit {@link Snapshot} (an O(n) buffer copy) on coalesced keystrokes — the
     * common case while typing a run of characters.
     */
    boolean beginsNewStep(Kind kind) {
        return kind == Kind.OTHER || kind != runKind || undo.isEmpty();
    }

    /**
     * Records the pre-edit state. Coalesces with the previous edit when both are the same TYPING/DELETING run, in which
     * case {@code before} is ignored (and may be null — see {@link #beginsNewStep}).
     */
    void record(Snapshot before, Kind kind) {
        redo.clear();
        if (beginsNewStep(kind)) {
            undo.push(before);
            while (undo.size() > MAX)
                undo.removeLast();
        }
        runKind = kind;
    }

    /** Ends the current coalescing run so the next edit starts a fresh undo step (e.g. after a cursor move). */
    void breakRun() {
        runKind = null;
    }

    Optional<Snapshot> undo(Snapshot current) {
        if (undo.isEmpty())
            return Optional.empty();
        redo.push(current);
        runKind = null;
        return Optional.of(undo.pop());
    }

    Optional<Snapshot> redo(Snapshot current) {
        if (redo.isEmpty())
            return Optional.empty();
        undo.push(current);
        runKind = null;
        return Optional.of(redo.pop());
    }

    void clear() {
        undo.clear();
        redo.clear();
        runKind = null;
    }
}
