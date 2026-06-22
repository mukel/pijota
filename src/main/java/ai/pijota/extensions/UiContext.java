package ai.pijota.extensions;

import ai.pijota.theme.AnsiTheme;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Frontend-agnostic UI for extensions. Each frontend (TUI, Telegram, Web) provides an implementation. Default methods
 * are safe no-ops — call them unconditionally; if no UI is attached they silently succeed.
 */
public interface UiContext {
    /** Set the main status bar text. */
    void setStatus(String message);

    /** Modal yes/no confirmation. Returns false when no UI is attached. */
    default CompletableFuture<Boolean> confirm(String question) {
        return CompletableFuture.completedFuture(false);
    }

    /** Modal text prompt with optional default. Returns the default when no UI is attached. */
    default CompletableFuture<String> prompt(String question, String defaultValue) {
        return CompletableFuture.completedFuture(defaultValue);
    }

    /** Show a transient notification/alert. */
    default void alert(String message) {}

    /** Current color theme. Returns null when no UI is attached. */
    default AnsiTheme theme() {
        return null;
    }

    /**
     * Shows an interactive single-choice selector. {@code render} supplies each item's display label; {@code onChoice}
     * runs on the UI thread with the chosen item. If the user cancels, it is not called. Callers work in terms of their
     * own domain objects — the selector never exposes indices.
     */
    default <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {}

    /** Opens a modal, scrollable, searchable read-only viewer over {@code lines} (e.g. a sub-agent log). */
    default void viewer(String title, List<String> lines) {}

    /**
     * Opens a viewer over a live {@code source} re-read periodically — for a still-running sub-agent's log it tails the
     * output (follow mode), updating as the agent works. {@code source} must be cheap and non-throwing.
     */
    default void viewer(String title, Supplier<List<String>> source) {}

    // ---- terminal frontends only (no-ops elsewhere) ----

    /**
     * Append styled lines to the terminal scrollback (operation output, rendered with the frontend's cursor bookkeeping
     * — never added to the conversation/session). No-op on non-terminal frontends. Style via {@link #theme}.
     */
    default void appendOutput(List<String> lines) {}

    /** Hand the terminal over to a child process (raw → cooked); reentrant. No-op on non-terminal frontends. */
    default void suspend() {}

    /**
     * Reclaim the terminal after {@link #suspend} and repaint the prompt; reentrant. No-op on non-terminal frontends.
     */
    default void resume() {}
}
