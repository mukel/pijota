package ai.pijota.tools;

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
        return Map.of("type", "object", "properties", Map.of("todos", Map.of("type", "array", "description",
                                        "The updated todo list", "items",
                                        Map.of("type", "object", "properties", Map.of("content", Map.of("type",
                                                                        "string", "description", "Brief description of the task"),
                                                                        "status",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Current status of the task: pending, in_progress, completed, cancelled"),
                                                                        "priority",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Priority level of the task: high, medium, low")),
                                                                        "required",
                                                                        List.of("content", "status", "priority")))),
                                        "required", List.of("todos"), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
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
    public ToolResultView renderResult(Map<String, Object> args, ToolResult result, String cwd) {
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

    /** Wraps this tool as a {@link Tool.ToolDefinition} so it can be registered by an extension (opt-in). */
    public Tool.ToolDefinition toDefinition() {
        return new Tool.ToolDefinition(name(), description(), schema(), (id, args, cwd) -> execute(id, args, cwd),
                                        this::renderResult).withGlyph(glyph());
    }
}
