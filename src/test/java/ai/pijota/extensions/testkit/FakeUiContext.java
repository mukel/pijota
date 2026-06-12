package ai.pijota.extensions.testkit;

import ai.pijota.extensions.UiContext;
import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class FakeUiContext implements UiContext {

    private final List<String> statuses = new ArrayList<>();
    private final AtomicReference<CompletableFuture<Boolean>> confirmResult = new AtomicReference<>(
                                    CompletableFuture.completedFuture(false));

    public List<String> statuses() {
        return List.copyOf(statuses);
    }

    public void setConfirmResult(boolean result) {
        confirmResult.set(CompletableFuture.completedFuture(result));
    }

    @Override
    public void setStatus(String message) {
        statuses.add(message);
    }

    @Override
    public void appendStatus(String message) {
        statuses.add("append:" + message);
    }

    @Override
    public void setStatus(String key, String message) {
        statuses.add(key + "=" + message);
    }

    @Override
    public void clearStatus(String key) {
        statuses.add("clear:" + key);
    }

    @Override
    public CompletableFuture<Boolean> confirm(String question) {
        return confirmResult.get();
    }

    @Override
    public CompletableFuture<String> prompt(String question, String defaultValue) {
        return CompletableFuture.completedFuture(defaultValue);
    }

    @Override
    public void alert(String message) {
        statuses.add("alert:" + message);
    }

    @Override
    public AnsiTheme theme() {
        return null;
    }
}
