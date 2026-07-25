package ai.pijota.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Thread-safe registry of tools, keyed by name in insertion order. Tools move through three explicit states:
 * registered (known), available (allowed by policy), and active (sent to the model).
 */
public final class ToolRegistry {

    // Guarded by this: tools may be registered dynamically and from background threads (e.g. MCP servers connecting in
    // parallel, or reconnecting) while the agent thread reads the active set each turn — so all access is
    // synchronized.
    // A LinkedHashMap preserves registration order; the per-turn cost of these short critical sections is negligible.
    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private ToolPolicy policy = ToolPolicy.UNRESTRICTED;
    private Set<String> activeNames;
    private ToolManifest.Snapshot cachedManifestSnapshot;

    private record ToolEntry(Tool tool, boolean builtin) {
    }

    public synchronized void register(Tool tool) {
        register(tool, false);
    }

    private synchronized void register(Tool tool, boolean builtin) {
        tools.put(tool.name(), new ToolEntry(tool, builtin));
        pruneActiveNames();
        cachedManifestSnapshot = null;
    }

    /** Register a first-party (non-extension) tool; distinguished only so {@code --no-builtin-tools} can target it. */
    public synchronized void registerBuiltin(Tool tool) {
        register(tool, true);
    }

    public synchronized void applyPolicy(ToolPolicy policy) {
        this.policy = policy == null ? ToolPolicy.UNRESTRICTED : policy;
        pruneActiveNames();
        cachedManifestSnapshot = null;
    }

    /** Removes a tool (and disables it). No-op if it was never registered. Used for dynamic tools (e.g. MCP). */
    public synchronized void unregister(String name) {
        tools.remove(name);
        pruneActiveNames();
        cachedManifestSnapshot = null;
    }

    public synchronized void setActiveTools(Set<String> names) {
        activeNames = names == null ? null : Set.copyOf(names);
        pruneActiveNames();
        cachedManifestSnapshot = null;
    }

    public synchronized List<Tool> registeredTools() {
        return tools.values().stream().map(ToolEntry::tool).toList();
    }

    public synchronized List<Tool> availableTools() {
        return tools.values().stream().filter(this::available).map(ToolEntry::tool).toList();
    }

    public synchronized List<Tool> activeTools() {
        if (activeNames == null)
            return availableTools();
        return availableTools().stream().filter(t -> activeNames.contains(t.name())).toList();
    }

    public synchronized List<Tool.ToolInfo> registeredToolInfo() {
        return registeredTools().stream().map(t -> new Tool.ToolInfo(t.name(), t.description())).toList();
    }

    public synchronized List<Tool.ToolInfo> availableToolInfo() {
        return availableTools().stream().map(t -> new Tool.ToolInfo(t.name(), t.description())).toList();
    }

    public synchronized List<Tool.ToolInfo> activeToolInfo() {
        return activeTools().stream().map(t -> new Tool.ToolInfo(t.name(), t.description())).toList();
    }

    public synchronized List<String> activeToolNames() {
        return activeTools().stream().map(Tool::name).toList();
    }

    public synchronized Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name)).filter(this::available).map(ToolEntry::tool);
    }

    public synchronized void abortRunningTools() {
        for (ToolEntry entry : tools.values())
            entry.tool().abort();
    }

    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, String toolName, Map<String, Object> arguments,
                                    String rawArgs, String sessionCwd) {
        return execute(toolCallId, toolName, arguments, rawArgs, sessionCwd, null);
    }

    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, String toolName, Map<String, Object> arguments,
                                    String rawArgs, String sessionCwd, Consumer<String> onProgress) {
        return get(toolName).flatMap(tool -> {
            var errors = validateArguments(tool, arguments);
            if (!errors.isEmpty()) {
                return Optional.of(CompletableFuture.completedFuture(
                                                Tool.ToolResult.error(formatValidationError(toolName, errors))));
            }
            return Optional.of(tool.execute(toolCallId, new Tool.Args(arguments), sessionCwd, onProgress));
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

    /**
     * Invalidates the cached provider-facing tool manifest. Call when a registered tool's metadata changes in place.
     */
    public synchronized void invalidateManifestSnapshot() {
        cachedManifestSnapshot = null;
    }

    synchronized ToolManifest.Snapshot cachedManifestSnapshot() {
        return cachedManifestSnapshot;
    }

    synchronized void cacheManifestSnapshot(ToolManifest.Snapshot snapshot) {
        cachedManifestSnapshot = snapshot;
    }

    private boolean available(ToolEntry entry) {
        var tool = entry.tool();
        return policy.keep(tool.name(), entry.builtin(), tool.defaultEnabled());
    }

    private void pruneActiveNames() {
        if (activeNames == null)
            return;
        var available = availableTools().stream().map(Tool::name).collect(java.util.stream.Collectors.toSet());
        activeNames = activeNames.stream().filter(available::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static ToolRegistry createDefault() {
        var registry = new ToolRegistry();
        registry.registerBuiltin(new ReadTool());
        registry.registerBuiltin(new WriteTool());
        registry.registerBuiltin(new EditTool());
        registry.registerBuiltin(new ApplyPatchTool());
        registry.registerBuiltin(new GlobTool());
        registry.registerBuiltin(new GrepTool());
        registry.registerBuiltin(new LsTool());
        registry.registerBuiltin(new SlashCommandTool());
        registry.registerBuiltin(new WebfetchTool());
        registry.registerBuiltin(new WebsearchTool());
        return registry;
    }
}
