package org.pijota.tools;

import org.pijota.llm.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The model's own checklist for multi-step work, kept in the session so progress survives a compaction. */
public final class TodoTool implements Tool {

    /**
     * One todo item. {@code status}: pending | in_progress | completed | cancelled. {@code priority}: high|medium|low.
     */
    public record Todo(String content, String status, String priority) {
    }

    private final Consumer<List<Todo>> onUpdate;

    public TodoTool() {
        this(todos -> {});
    }

    /** {@code onUpdate} receives the full list on every write, letting a frontend surface a live todo panel. */
    public TodoTool(Consumer<List<Todo>> onUpdate) {
        this.onUpdate = onUpdate;
    }

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public String glyph() {
        return ToolGlyphs.AGENT;
    }

    @Override
    public String description() {
        return "Maintain a structured todo list for multi-step work. Use it when a task needs 3+ distinct steps "
                                        + "or the user gives multiple tasks; skip it for single/trivial or purely conversational requests. "
                                        + "States: pending, in_progress (exactly ONE at a time), completed, cancelled. Update status in "
                                        + "real time and mark completed only after the work, including any verification, is actually done.";
    }

    @Override
    public Map<String, Object> schema() {
        var todoItem = ToolSchema.object().str("content", "Brief description of the task", true).str("status",
                                        "Current status of the task: pending, in_progress, completed, cancelled", true)
                                        .str("priority", "Priority level of the task: high, medium, low", true)
                                        .buildOpen();
        return ToolSchema.object().array("todos", "The updated todo list", true, todoItem).build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object todosRaw = args.get("todos");
                if (!(todosRaw instanceof List<?> rawList))
                    return ToolResult.error("todos must be an array");
                var parsed = new ArrayList<Todo>();
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> m)
                        parsed.add(new Todo(str(m, "content"), str(m, "status"), str(m, "priority")));
                }
                onUpdate.accept(List.copyOf(parsed)); // replace-the-whole-list semantics, mirrored to the live panel
                return ToolResult.success(render(parsed));
            } catch (Exception e) {
                return ToolResult.error("todo error: " + e.getMessage());
            }
        });
    }

    /**
     * Renders the list as the tool result: an icon-prefixed line per todo, then an {@code N todos (M completed)} tally.
     */
    static String render(List<Todo> todos) {
        int completed = 0;
        var sb = new StringBuilder();
        for (Todo t : todos) {
            if ("completed".equals(t.status()))
                completed++;
            sb.append(icon(t.status())).append(' ').append(t.content());
            if (!t.priority().isBlank())
                sb.append("  (").append(t.priority()).append(')');
            sb.append('\n');
        }
        sb.append(todos.size()).append(" todos (").append(completed).append(" completed)");
        return sb.toString();
    }

    /** Status icon for a todo state, shared with the live panel so both stay in sync. */
    public static String icon(String status) {
        return switch (status) {
            case "completed" -> "[✓]";
            case "in_progress" -> "[~]";
            case "cancelled" -> "[−]";
            default -> "[ ]";
        };
    }

    private static String str(Map<?, ?> m, String key) {
        return m.get(key) instanceof String s ? s : "";
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        if (result.isError())
            return ToolResultView.of(result.content());
        String content = result.content();
        if (content == null || content.isBlank())
            return ToolResultView.of("");
        var lines = content.lines().toList();
        if (lines.isEmpty())
            return ToolResultView.of("");
        String summary = lines.getLast();
        var body = lines.subList(0, lines.size() - 1).stream().map(line -> "│ " + line).toList();
        return ToolResultView.of(summary, body);
    }
}
