package org.pijota.extensions;

import org.pijota.theme.AnsiTheme;
import org.pijota.tools.SlashCommandTool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Frontend-agnostic UI for extensions. Each frontend (TUI, Telegram, Web) provides an implementation. Default methods
 * are safe no-ops. Call them unconditionally; if no UI is attached they silently succeed.
 */
public interface UiContext {
    /** Set the main status bar text. */
    void setStatus(String message);

    /**
     * Request a repaint of any live UI chrome (status lines, footer). For async state changes that don't produce
     * output: without a repaint an append-only frontend shows the state as of its last redraw.
     */
    default void refresh() {}

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

    /**
     * Current color theme. Never null. Non-terminal frontends get a no-color theme, so {@code theme().styled(…)} is
     * always safe (it returns text unchanged there). Terminal frontends override with the live theme.
     */
    default AnsiTheme theme() {
        return AnsiTheme.plain();
    }

    /**
     * Shows an interactive single-choice selector. {@code render} supplies each item's display label; {@code onChoice}
     * runs on the UI thread with the chosen item. If the user cancels, it is not called. Callers work in terms of their
     * own domain objects. The selector never exposes indices.
     */
    default <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {}

    /**
     * Ask one or more questions (the {@code /question} tool) and return the combined answer. The default makes any
     * frontend interactive by decomposing each question into the primitives every frontend implements, {@link #select}
     * for an option list, {@link #prompt} for free text, and chaining them. A frontend may override with a single
     * richer widget (e.g. options plus a free-text field, or multi-select). With no UI attached, the answers resolve to
     * the prompt defaults.
     */
    default CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        CompletableFuture<String> chain = CompletableFuture.completedFuture("");
        for (SlashCommandTool.Question q : questions)
            chain = chain.thenCompose(acc -> askOne(q).thenApply(answer -> acc + (acc.isEmpty() ? "" : "\n")
                                            + q.question() + " → " + answer));
        return chain;
    }

    /**
     * One question via the shared primitives: a non-empty option list → {@link #select}, otherwise → {@link #prompt}.
     */
    private CompletableFuture<String> askOne(SlashCommandTool.Question q) {
        if (q.options() == null || q.options().isEmpty())
            return prompt(q.question(), "");
        var answer = new CompletableFuture<String>();
        select(q.question(), q.options(), SlashCommandTool.Option::label, opt -> answer.complete(opt.label()));
        return answer;
    }

    /**
     * Release any interaction still blocking a tool (e.g. a pending {@code /question}) by resolving it as cancelled.
     * Called when this frontend goes away so the agent turn can't hang. No-op for frontends with nothing pending.
     */
    default void cancelPending() {}

    /** Submit a user message through the active frontend, preserving normal echo/busy/error handling. */
    default void submitUserMessage(String content) {}

    /** Opens a modal, scrollable, searchable read-only viewer over {@code lines} (e.g. a sub-agent log). */
    default void viewer(String title, List<String> lines) {}

    /**
     * Opens a viewer over a live {@code source} re-read periodically. For a still-running sub-agent's log it tails the
     * output (follow mode), updating as the agent works. {@code source} must be cheap and non-throwing.
     */
    default void viewer(String title, Supplier<List<String>> source) {}

    // ---- terminal frontends only (no-ops elsewhere) ----

    /**
     * Append styled lines to the terminal scrollback (operation output, rendered with the frontend's cursor bookkeeping
     * (never added to the conversation/session). No-op on non-terminal frontends. Style via {@link #theme}.
     */
    default void appendOutput(List<String> lines) {}

    /** Hand the terminal over to a child process (raw → cooked); reentrant. No-op on non-terminal frontends. */
    /**
     * Whether this frontend has a draft the user edits before sending. False for chat frontends, which have nowhere to
     * put one.
     *
     * <p>
     * The query exists so speculative work can decline to start. A no-op {@link #offerSuggestion} would be enough to
     * render nothing, but producing the argument may cost an inference call, and spending it to throw the result away
     * is not something a frontend should have to opt out of after the fact.
     */
    default boolean acceptsPromptDrafts() {
        return false;
    }

    /**
     * Offers {@code text} as a suggestion for the user's next message. Advisory: the frontend shows it only if that
     * still makes sense (an empty draft, nothing typed since), and the user accepts it explicitly. Never sends.
     */
    default void offerSuggestion(String text) {}

    default void suspend() {}

    /**
     * Reclaim the terminal after {@link #suspend} and repaint the prompt; reentrant. No-op on non-terminal frontends.
     */
    default void resume() {}
}
