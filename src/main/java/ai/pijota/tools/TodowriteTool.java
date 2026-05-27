package ai.pijota.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TodowriteTool implements Tool {

    @Override public String name() { return "todowrite"; }

    @Override
    public String description() {
        return "Create and maintain a structured task list for the current coding session. "
            + "Tracks progress, organizes multi-step work, and surfaces status to the user.\n\n"
            + "## When to use\n"
            + "Use proactively when:\n"
            + "- The task requires 3+ distinct steps or actions (not just 3 tool calls for a single conceptual step)\n"
            + "- The work is non-trivial and benefits from planning\n"
            + "- The user provides multiple tasks (numbered or comma-separated) or explicitly asks for a todo list\n"
            + "- New instructions arrive - capture them as todos\n"
            + "- You start a task - mark it `in_progress` (only one at a time) before working\n"
            + "- You finish a task - mark it `completed` and add any follow-ups discovered during the work\n\n"
            + "## When NOT to use\n"
            + "Skip when:\n"
            + "- The work is a single, straightforward task (or <3 trivial steps)\n"
            + "- The request is purely informational or conversational\n"
            + "- Tracking adds no organizational value\n\n"
            + "## States\n"
            + "- `pending` - not started\n"
            + "- `in_progress` - actively working (exactly ONE at a time)\n"
            + "- `completed` - finished successfully\n"
            + "- `cancelled` - no longer needed\n\n"
            + "## Rules\n"
            + "- Update status in real time; don't batch completions\n"
            + "- Mark `completed` only after the required work is actually done, including any required verification. Never based on intent.\n"
            + "- Keep exactly one `in_progress` while work remains\n"
            + "- If blocked or partial, keep it `in_progress` and add a follow-up todo describing the blocker\n"
            + "- Preserve user-provided commands verbatim (flags, args, order)\n"
            + "- Items should be specific and actionable; break large work into smaller steps";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "todos", Map.of("type", "array",
                    "description", "The updated todo list",
                    "items", Map.of("type", "object",
                        "properties", Map.of(
                            "content", Map.of("type", "string",
                                "description", "Brief description of the task"),
                            "status", Map.of("type", "string",
                                "description", "Current status of the task: pending, in_progress, completed, cancelled"),
                            "priority", Map.of("type", "string",
                                "description", "Priority level of the task: high, medium, low")
                        ),
                        "required", List.of("content", "status", "priority")
                    )
                )
            ),
            "required", List.of("todos"),
            "additionalProperties", false
        );
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
                    if (!(item instanceof Map<?, ?> m)) continue;
                    count++;
                    String status = m.get("status") instanceof String s ? s : "";
                    String content = m.get("content") instanceof String s ? s : "";
                    String priority = m.get("priority") instanceof String s ? s : "";
                    if ("completed".equals(status)) completed++;
                    String icon = switch (status) {
                        case "completed" -> "[✓]";
                        case "in_progress" -> "[~]";
                        case "cancelled" -> "[−]";
                        default -> "[ ]";
                    };
                    sb.append(icon).append(" ").append(content);
                    if (!priority.isBlank()) sb.append("  (").append(priority).append(")");
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
        if (result.isError()) return ToolResultView.of(result.content());
        String content = result.content();
        if (content == null || content.isBlank()) return ToolResultView.of("");
        var lines = content.lines().toList();
        if (lines.isEmpty()) return ToolResultView.of("");
        String summary = lines.getLast();
        var body = lines.subList(0, Math.max(0, lines.size() - 1)).stream()
            .map(line -> "│ " + line)
            .toList();
        return ToolResultView.of(summary, body);
    }
}
