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

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> enabledNames = new HashSet<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        if (tool.defaultEnabled()) enabledNames.add(tool.name());
    }

    public void enableOnly(Collection<String> names) { enabledNames.clear(); enabledNames.addAll(names); }

    public List<Tool> enabledTools() {
        return tools.values().stream().filter(t -> enabledNames.contains(t.name())).toList();
    }

    public List<Tool.ToolInfo> allTools() {
        return tools.values().stream()
            .map(t -> new Tool.ToolInfo(t.name(), t.description()))
            .toList();
    }

    public List<Tool.ToolInfo> activeTools() {
        return enabledTools().stream()
            .map(t -> new Tool.ToolInfo(t.name(), t.description()))
            .toList();
    }

    public List<String> enabledToolNames() {
        return enabledTools().stream().map(Tool::name).toList();
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public void abortRunningTools() {
        for (Tool tool : tools.values()) tool.abort();
    }

    public CompletableFuture<Tool.ToolResult> execute(
            String toolCallId, String toolName,
            Map<String, Object> arguments, String rawArgs, String sessionCwd) {
        return get(toolName)
            .flatMap(tool -> {
                var errors = validateArguments(tool, arguments);
                if (!errors.isEmpty()) {
                    String raw = rawArgs != null && rawArgs.length() > 500
                        ? rawArgs.substring(0, 500) + "..." : rawArgs;
                    return Optional.of(CompletableFuture.completedFuture(
                        Tool.ToolResult.error("Invalid arguments: " + String.join("; ", errors)
                            + "\n\nReceived: " + raw)));
                }
                return Optional.of(tool.execute(toolCallId, arguments, sessionCwd));
            })
            .orElseGet(() -> CompletableFuture.completedFuture(
                Tool.ToolResult.error("Unknown tool: " + toolName)));
    }

    private static List<String> validateArguments(Tool tool, Map<String, Object> args) {
        var errors = new ArrayList<String>();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.schema();
        if (schema == null) return errors;

        @SuppressWarnings("unchecked")
        var required = (List<String>) schema.get("required");
        if (required != null) {
            for (String key : required) {
                if (hasArg(args, key, schema)) continue;
                errors.add("Missing required field: " + key);
            }
        }
        return errors;
    }

    private static boolean hasArg(Map<String, Object> args, String key, Map<String, Object> schema) {
        if (args.containsKey(key) && args.get(key) != null
            && (!(args.get(key) instanceof String s) || !s.isBlank())) return true;
        if ("path".equals(key)) {
            if (args.containsKey("filePath") && args.get("filePath") != null
                && (!(args.get("filePath") instanceof String s) || !s.isBlank())) return true;
            if (args.containsKey("file_path") && args.get("file_path") != null
                && (!(args.get("file_path") instanceof String s) || !s.isBlank())) return true;
        }
        if ("edits".equals(key)) {
            return hasString(args, "oldText") && hasString(args, "newText")
                || hasString(args, "oldString") && hasString(args, "newString");
        }
        return false;
    }

    private static boolean hasString(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s && !s.isBlank();
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
        registry.register(new TodowriteTool());
        registry.register(new TranscendenceTool());
        return registry;
    }
}
