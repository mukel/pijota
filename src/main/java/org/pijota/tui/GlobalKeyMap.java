package org.pijota.tui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A flat table of global single-key bindings (each a distinct key → action) consulted at one point in the input loop.
 * Replacing a run of scattered {@code if (key == …) { …; continue; }} blocks with one declarative table keeps the
 * dispatch order obvious and the bindings testable, without changing behavior. The keys are mutually distinct, so their
 * relative order never mattered.
 */
final class GlobalKeyMap {

    private final Map<Key, Runnable> bindings = new LinkedHashMap<>();

    GlobalKeyMap bind(Key key, Runnable action) {
        bindings.put(key, action);
        return this;
    }

    /** Runs the action bound to {@code key}, returning true if one existed. */
    boolean dispatch(Key key) {
        Runnable action = bindings.get(key);
        if (action == null)
            return false;
        action.run();
        return true;
    }
}
