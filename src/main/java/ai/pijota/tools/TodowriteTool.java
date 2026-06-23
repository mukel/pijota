package ai.pijota.tools;

import ai.pijota.llm.ToolSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TodowriteTool implements Tool {

    @Override
    public String name() {
        return "todowrite";
    }

    @Override
    public String glyph() {
        return "📋";
    }

    @Override
    public String description() {
        return "Maintain a structured todo list for multi-step work. Use it when a task needs 3+ distinct steps "
                                        + "or the user gives multiple tasks; skip it for single/trivial or purely conversational requests. "
                                        + "States: pending, in_progress (exactly ONE at a time), completed, cancelled. Update status in "
                                        + "real time and mark completed only after the work — including any verification — is actually done.";
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
                if (!(todosRaw instanceof List<?> todos)) {
                    return ToolResult.error("todos must be an array");
                }
                int count = 0;
                int completed = 0;
                var sb = new StringBuilder();
                for (Object item : todos) {
                    if (!(item instanceof Map<?, ?> m))
                        continue;
                    count++;
                    String status = m.get("status") instanceof String s ? s : "";
                    String content = m.get("content") instanceof String s ? s : "";
                    String priority = m.get("priority") instanceof String s ? s : "";
                    if ("completed".equals(status))
                        completed++;
                    String icon = switch (status) {
                        case "completed" -> "[✓]";
                        case "in_progress" -> "[~]";
                        case "cancelled" -> "[−]";
                        default -> "[ ]";
                    };
                    sb.append(icon).append(" ").append(content);
                    if (!priority.isBlank())
                        sb.append("  (").append(priority).append(")");
                    sb.append('\n');
                }
                sb.append(count).append(" todos (").append(completed).append(" completed)");
                return ToolResult.success(sb.toString());
            } catch (Exception e) {
                return ToolResult.error("todowrite error: " + e.getMessage());
            }
        });
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
