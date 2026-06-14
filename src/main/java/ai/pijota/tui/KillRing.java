package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * Emacs-style kill ring: text removed by the word/line kill commands is pushed here instead of being lost, so it can be
 * yanked back (possibly elsewhere). {@link #yank()} returns the most recent kill; repeated {@link #yankPop()} walk to
 * older kills, wrapping around — the editor uses this to cycle a just-yanked region.
 */
final class KillRing {

    private static final int MAX = 32;

    private final List<String> entries = new ArrayList<>();
    private int cursor = -1; // index of the last yanked entry, for yank-pop cycling

    /** Pushes killed text onto the ring. Empty kills (e.g. a line-merge) are ignored. */
    void add(String text) {
        if (text == null || text.isEmpty())
            return;
        entries.add(text);
        while (entries.size() > MAX)
            entries.removeFirst();
        cursor = -1;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The most recent kill; anchors the yank-pop cursor. Returns null if the ring is empty. */
    String yank() {
        if (entries.isEmpty())
            return null;
        cursor = entries.size() - 1;
        return entries.get(cursor);
    }

    /** The next older kill (wrapping), for yank-pop. Returns null if nothing has been yanked yet. */
    String yankPop() {
        if (entries.isEmpty() || cursor < 0)
            return null;
        cursor = (cursor - 1 + entries.size()) % entries.size();
        return entries.get(cursor);
    }
}
