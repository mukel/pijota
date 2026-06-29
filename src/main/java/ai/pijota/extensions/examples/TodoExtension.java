package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.tools.TodowriteTool;
import ai.pijota.tools.TodowriteTool.Todo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in structured todo list via the {@code todowrite} tool, plus a live, always-visible panel of the current list
 * (frontend-agnostic status lines — terminal, web, Telegram). The tool adds weight to every prompt and only helps
 * multi-step work, so it is OFF by default — enable with the {@code --todo} flag.
 */
public final class TodoExtension implements Extension {

    private static final int MAX_PANEL_ROWS = 8; // keep the footer legible; summarize the overflow

    private final AtomicReference<List<Todo>> todos = new AtomicReference<>(List.of());

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public boolean enabledByDefault() {
        return false; // opt-in: --todo
    }

    @Override
    public String description() {
        return "Opt-in structured todo list (todowrite) with a live panel. Enable with --todo.";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerTool(new TodowriteTool(todos::set)); // every write replaces the panel's list
        api.registerStatusLines(() -> renderPanel(todos.get()));
    }

    /** The live panel: a header with the done/total tally, then each todo. Empty when there's nothing left to show. */
    static List<String> renderPanel(List<Todo> todos) {
        if (todos.isEmpty())
            return List.of();
        long done = todos.stream().filter(t -> "completed".equals(t.status()) || "cancelled".equals(t.status()))
                                        .count();
        if (done == todos.size())
            return List.of(); // all wrapped up — don't clutter the footer with a finished list
        var lines = new ArrayList<String>();
        lines.add("📋 todos · " + done + "/" + todos.size());
        int shown = Math.min(todos.size(), MAX_PANEL_ROWS);
        for (int i = 0; i < shown; i++)
            lines.add("  " + TodowriteTool.icon(todos.get(i).status()) + " " + todos.get(i).content());
        if (todos.size() > MAX_PANEL_ROWS)
            lines.add("   … +" + (todos.size() - MAX_PANEL_ROWS) + " more");
        return lines;
    }
}
