package ai.pijota.extensions;

import ai.pijota.theme.AnsiTheme;

import java.util.concurrent.CompletableFuture;

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
    default AnsiTheme theme() { return null; }
}
