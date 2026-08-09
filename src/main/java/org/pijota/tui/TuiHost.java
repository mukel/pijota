package org.pijota.tui;

import java.util.List;

/**
 * The shared primitive surface every TUI command handler builds on: append output, style text, repaint the prompt, and
 * drive the busy lifecycle. Each handler's own {@code Host} extends this with only the extra capabilities it needs, so
 * a handler depends on a narrow contract while the common primitives are declared exactly once. It exposes
 * <em>capabilities</em>, never the TUI's internals (terminal, fields, layout), so no implementation detail leaks
 * through it.
 *
 * <p>
 * Every primitive has an inert default (mirroring {@link org.pijota.extensions.UiContext}) so an implementer, chiefly a
 * test double, overrides only the capabilities it exercises. The live TUI overrides them all.
 */
interface TuiHost {

    /** Appends already-styled lines to the scrollback. */
    default void appendPlain(List<String> lines) {}

    default void appendError(String message) {}

    default void appendStatus(String message) {}

    /** Styles {@code text} with the named theme slot; the default returns it unstyled. */
    default String styled(String slot, String text) {
        return text;
    }

    /** Repaints the prompt region (and live tail). */
    default void redrawPrompt() {}

    default void clearPrompt() {}

    /** A message that shows for {@code millis} and then goes: feedback on a keystroke, not a line of the transcript. */
    default void setTransientStatus(String message, long millis) {}

    default boolean busy() {
        return false;
    }

    /** Marks the start of a busy phase; returns a generation token to pass to {@link #endBusy}. */
    default long beginBusy(String phase) {
        return 0;
    }

    /** Ends the busy phase started by the matching {@link #beginBusy}. */
    default void endBusy(long generation) {}

    /** Renders {@code text} as the user-echo lines shown when a command is submitted. */
    default List<String> promptEcho(String text) {
        return List.of();
    }

    /** One sentence for one condition. Nine call sites had drifted into two phrasings of it. */
    static String busyRefusal(String command) {
        return command + " is unavailable while a turn is running";
    }

    /**
     * Reports a command that is valid but cannot run right now, and says whether it was refused, so the call sites
     * collapse to {@code if (refuseWhileBusy("/model")) return;}.
     *
     * <p>
     * Amber, not red. Red in this interface means something failed -- a write that did not happen, a non-zero exit --
     * and a command declining to start while a turn is running has failed at nothing. Spending the failure colour on it
     * is how red stops meaning anything.
     */
    default boolean refuseWhileBusy(String command) {
        if (!busy())
            return false;
        // The status line, not the transcript. A declined keystroke is not part of the conversation, and appending
        // it put "/sessions is unavailable while a turn is running" directly under the user's own message, where it
        // read as something that had been sent. It also landed mid-stream, interleaved with the model's output.
        setTransientStatus(busyRefusal(command), 3000);
        return true;
    }
}
