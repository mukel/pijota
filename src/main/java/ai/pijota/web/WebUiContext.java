package ai.pijota.web;

import ai.pijota.extensions.UiContext;
import ai.pijota.tools.SlashCommandTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Implements the agent's interactive callbacks ({@link UiContext}) over the web frontend. Each interactive request
 * (confirm/prompt/select) is pushed to the browser as a {@code dialog} event carrying a unique id; the browser POSTs
 * the answer back to {@code /answer}, which calls {@link #resolve} to complete the waiting future. Status and alerts
 * become {@code status} events. The constructor takes the server's broadcaster so this class stays decoupled from the
 * HTTP plumbing.
 */
public final class WebUiContext implements UiContext {

    private final BiConsumer<String, Object> emit;
    private final Map<String, Consumer<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    WebUiContext(BiConsumer<String, Object> emit) {
        this.emit = emit;
    }

    @Override
    public void setStatus(String message) {
        // Unkeyed status is a discrete notification (e.g. command output) — surface it inline in the transcript.
        status(message, false);
    }

    @Override
    public void alert(String message) {
        status(message, true);
    }

    private void status(String message, boolean alert) {
        var map = new java.util.HashMap<String, Object>();
        map.put("message", message == null ? "" : message);
        if (alert)
            map.put("alert", true);
        emit.accept("status", map);
    }

    // Keyed status (setStatus(key,…)/clearStatus) is intentionally NOT overridden: the runtime stores those in its own
    // map and never routes them through UiContext — WebServer polls statusLines() for them instead (TUI parity).

    @Override
    public void viewer(String title, List<String> lines) {
        emit.accept("viewer", Map.of("title", title == null ? "" : title, "lines", lines == null ? List.of() : lines));
    }

    @Override
    public void viewer(String title, java.util.function.Supplier<List<String>> source) {
        // The web has no live-tailing viewer; snapshot once. Cheap + non-throwing per the UiContext contract.
        viewer(title, source == null ? List.of() : source.get());
    }

    @Override
    public CompletableFuture<Boolean> confirm(String question) {
        var future = new CompletableFuture<Boolean>();
        String id = open(data -> future.complete("yes".equals(data)));
        emit.accept("dialog", Map.of("id", id, "kind", "confirm", "question", question));
        return future;
    }

    @Override
    public CompletableFuture<String> prompt(String question, String defaultValue) {
        var future = new CompletableFuture<String>();
        String id = open(future::complete);
        emit.accept("dialog", Map.of("id", id, "kind", "prompt", "question", question, "default",
                                        defaultValue == null ? "" : defaultValue));
        return future;
    }

    @Override
    public <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {
        var labels = new ArrayList<String>(items.size());
        for (T item : items)
            labels.add(render.apply(item));
        String id = open(data -> {
            int idx = parseIndex(data);
            if (idx >= 0 && idx < items.size())
                onChoice.accept(items.get(idx)); // callers never see indices; we map back to their domain object
        });
        emit.accept("dialog", Map.of("id", id, "kind", "select", "title", title, "options", labels));
    }

    /**
     * Richer than the default select/prompt decomposition: one {@code question} dialog carrying every question with its
     * options and {@code multiple} flag. The browser renders option buttons plus a free-text field per question and
     * POSTs back a single combined answer string.
     */
    @Override
    public CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        var future = new CompletableFuture<String>();
        String id = open(future::complete);
        var payload = new ArrayList<Map<String, Object>>(questions.size());
        for (SlashCommandTool.Question q : questions) {
            var options = new ArrayList<String>();
            for (SlashCommandTool.Option o : q.options())
                options.add(o.label());
            payload.add(Map.of("question", q.question(), "header", q.header() == null ? "" : q.header(), "options",
                                            options, "multiple", q.multiple()));
        }
        emit.accept("dialog", Map.of("id", id, "kind", "question", "questions", payload));
        return future;
    }

    /** Completes the dialog with the given id; no-op if it already fired or never existed. */
    void resolve(String id, String value) {
        if (id == null)
            return;
        var handler = pending.remove(id);
        if (handler != null)
            handler.accept(value);
    }

    private String open(Consumer<String> handler) {
        String id = Long.toString(ids.incrementAndGet());
        pending.put(id, handler);
        return id;
    }

    private static int parseIndex(String data) {
        try {
            return Integer.parseInt(data);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
