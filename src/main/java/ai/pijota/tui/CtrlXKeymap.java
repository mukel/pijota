package ai.pijota.tui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code C-x} prefix keymap. A single ordered table of (key → label, action) bindings drives both the
 * discoverability hint shown after {@code C-x} and the dispatch of the following key, so the two can never drift apart.
 * Matching is case-insensitive.
 */
final class CtrlXKeymap {

    private record Binding(char key, String label, Runnable action) {
    }

    private final Map<Character, Binding> bindings = new LinkedHashMap<>();

    CtrlXKeymap bind(char key, String label, Runnable action) {
        char k = Character.toLowerCase(key);
        bindings.put(k, new Binding(k, label, action));
        return this;
    }

    /** The hint line, e.g. {@code "C-x  m:model  l:sessions  …"}, derived from the bindings in order. */
    String hint() {
        var sb = new StringBuilder("C-x");
        for (Binding b : bindings.values())
            sb.append("  ").append(b.key()).append(':').append(b.label());
        return sb.toString();
    }

    /** Runs the action bound to {@code key} (case-insensitive); returns true if a binding existed. */
    boolean dispatch(char key) {
        Binding b = bindings.get(Character.toLowerCase(key));
        if (b == null)
            return false;
        b.action().run();
        return true;
    }
}
