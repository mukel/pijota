package ai.pijota.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ToolRegistry {

    // Guarded by this: tools may be registered dynamically and from background threads (e.g. MCP servers connecting in
    // parallel, or reconnecting) while the agent thread reads the enabled set each turn — so all access is
    // synchronized.
    // A LinkedHashMap preserves registration order; the per-turn cost of these short critical sections is negligible.
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> enabledNames = new HashSet<>();

    public synchronized void register(Tool tool) {
        tools.put(tool.name(), tool);
        if (tool.defaultEnabled())
            enabledNames.add(tool.name());
    }

    /** Removes a tool (and disables it). No-op if it was never registered. Used for dynamic tools (e.g. MCP). */
    public synchronized void unregister(String name) {
        tools.remove(name);
        enabledNames.remove(name);
    }

    public synchronized void enableOnly(Collection<String> names) {
        enabledNames.clear();
        enabledNames.addAll(names);
    }

    public synchronized List<Tool> enabledTools() {
        return tools.values().stream().filter(t -> enabledNames.contains(t.name())).toList();
    }

    public synchronized List<Tool.ToolInfo> allTools() {
        return tools.values().stream().map(t -> new Tool.ToolInfo(t.name(), t.description())).toList();
    }

    public synchronized List<Tool.ToolInfo> activeTools() {
        return enabledTools().stream().map(t -> new Tool.ToolInfo(t.name(), t.description())).toList();
    }

    public synchronized List<String> enabledToolNames() {
        return enabledTools().stream().map(Tool::name).toList();
    }

    public synchronized Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public synchronized void abortRunningTools() {
        for (Tool tool : tools.values())
            tool.abort();
    }

    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, String toolName, Map<String, Object> arguments,
                                    String rawArgs, String sessionCwd) {
        return get(toolName).flatMap(tool -> {
            var errors = validateArguments(tool, arguments);
            if (!errors.isEmpty()) {
                return Optional.of(CompletableFuture.completedFuture(
                                                Tool.ToolResult.error(formatValidationError(toolName, errors))));
            }
            return Optional.of(tool.execute(toolCallId, arguments, sessionCwd));
        }).orElseGet(() -> CompletableFuture.completedFuture(Tool.ToolResult.error("Unknown tool: " + toolName)));
    }

    private static String formatValidationError(String toolName, List<String> errors) {
        var sb = new StringBuilder("Invalid arguments for ").append(toolName).append(":\n");
        for (String e : errors)
            sb.append("- ").append(e).append('\n');
        sb.append("Retry with arguments matching the ").append(toolName).append(" schema.");
        return sb.toString();
    }

    private static List<String> validateArguments(Tool tool, Map<String, Object> args) {
        var errors = new ArrayList<String>();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.schema();
        if (schema == null)
            return errors;

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        if (properties == null)
            return errors;

        @SuppressWarnings("unchecked")
        var required = (List<String>) schema.get("required");

        for (var entry : properties.entrySet()) {
            String field = (String) entry.getKey();
            @SuppressWarnings("unchecked")
            var fieldSchema = (Map<String, Object>) entry.getValue();
            String type = fieldSchema.get("type") instanceof String s ? s : null;
            if (type == null)
                continue;

            Object value = args.get(field);
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (required != null && required.contains(field) && !hasAliasArg(args, field)) {
                    errors.add(field + " is required");
                }
                continue;
            }

            String typeError = checkType(field, value, type);
            if (typeError != null)
                errors.add(typeError);
        }
        return errors;
    }

    private static String checkType(String field, Object value, String type) {
        return switch (type) {
            case "string" -> {
                if (!(value instanceof String))
                    yield field + ": expected string, got " + describe(value);
                yield null;
            }
            case "integer", "number" -> {
                if (!(value instanceof Number))
                    yield field + ": expected " + type + ", got " + describe(value);
                yield null;
            }
            case "boolean" -> {
                if (!(value instanceof Boolean))
                    yield field + ": expected boolean, got " + describe(value);
                yield null;
            }
            case "array" -> {
                if (!(value instanceof List<?>))
                    yield field + ": expected array, got " + describe(value);
                yield null;
            }
            default -> null;
        };
    }

    private static boolean hasAliasArg(Map<String, Object> args, String field) {
        if ("path".equals(field)) {
            return hasStringArg(args, "filePath") || hasStringArg(args, "file_path");
        }
        if ("edits".equals(field)) {
            return hasStringArg(args, "oldText") && hasStringArg(args, "newText")
                                            || hasStringArg(args, "oldString") && hasStringArg(args, "newString");
        }
        return false;
    }

    private static boolean hasStringArg(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s && !s.isBlank();
    }

    private static String describe(Object value) {
        if (value == null)
            return "null";
        if (value instanceof String s)
            return "string \"" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "\"";
        if (value instanceof Number n)
            return "number " + n;
        if (value instanceof Boolean b)
            return "boolean " + b;
        return value.getClass().getSimpleName();
    }

    public static ToolRegistry createDefault() {
        var registry = new ToolRegistry();
        registry.register(new ReadTool());
        registry.register(new WriteTool());
        registry.register(new EditTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        registry.register(new LsTool());
        registry.register(new SlashCommandTool());
        registry.register(new WebfetchTool());
        registry.register(new WebsearchTool());
        registry.register(new TranscendenceTool());
        registry.register(new WhoamiTool());
        return registry;
    }
}
