package ai.pijota.extensions;

import ai.pijota.theme.AnsiTheme;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public interface UiContext {
    void setStatus(String message);

    default void appendStatus(String message) {}

    default void setStatus(String key, String message) {}

    default void clearStatus(String key) {}

    default CompletableFuture<Boolean> confirm(String question) {
        return CompletableFuture.completedFuture(false);
    }

    default CompletableFuture<String> prompt(String question, String defaultValue) {
        return CompletableFuture.completedFuture(defaultValue);
    }

    default void alert(String message) {}

    default AnsiTheme theme() {
        return null;
    }

    /**
     * Shows an interactive single-choice selector. {@code render} supplies each item's display label; {@code onChoice}
     * runs on the UI thread with the chosen item. If the user cancels, it is not called. Callers work in terms of their
     * own domain objects — the selector never exposes indices.
     */
    default <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {}
}
