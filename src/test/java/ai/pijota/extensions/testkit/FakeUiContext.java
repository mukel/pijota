package ai.pijota.extensions.testkit;

import ai.pijota.extensions.UiContext;
import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class FakeUiContext implements UiContext {

    private final List<String> statuses = new ArrayList<>();
    private final AtomicReference<CompletableFuture<Boolean>> confirmResult = new AtomicReference<>(
                                    CompletableFuture.completedFuture(false));
    private final List<String> selectTitles = new ArrayList<>();
    private List<String> lastSelectLabels = List.of();
    private int selectChoice = -1;
    private final List<String> viewerTitles = new ArrayList<>();
    private List<String> lastViewerLines = List.of();

    public List<String> statuses() {
        return List.copyOf(statuses);
    }

    public void setConfirmResult(boolean result) {
        confirmResult.set(CompletableFuture.completedFuture(result));
    }

    /** Index that the next {@link #select} call will auto-choose; negative means the user cancels. */
    public void setSelectChoice(int index) {
        this.selectChoice = index;
    }

    public List<String> selectTitles() {
        return List.copyOf(selectTitles);
    }

    /** Display labels passed to the most recent {@link #select} call. */
    public List<String> lastSelectLabels() {
        return List.copyOf(lastSelectLabels);
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

    @Override
    public <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {
        selectTitles.add(title);
        lastSelectLabels = items.stream().map(render).toList();
        if (selectChoice >= 0 && selectChoice < items.size())
            onChoice.accept(items.get(selectChoice));
    }

    @Override
    public void viewer(String title, List<String> lines) {
        viewerTitles.add(title);
        lastViewerLines = List.copyOf(lines);
    }

    public List<String> viewerTitles() {
        return List.copyOf(viewerTitles);
    }

    /** Lines passed to the most recent {@link #viewer} call. */
    public List<String> lastViewerLines() {
        return lastViewerLines;
    }
}
