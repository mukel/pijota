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
 * Every primitive has an inert default (mirroring {@link org.pijota.extensions.UiContext}) so an implementer — chiefly
 * a test double — overrides only the capabilities it exercises. The live TUI overrides them all.
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
}
