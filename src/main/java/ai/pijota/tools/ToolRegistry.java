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

/**
 * Thread-safe registry of tools, keyed by name in insertion order, where the enabled set decides which are visible to
 * the model. Supports dynamic registration/deregistration for tools that connect and disconnect at runtime (e.g. MCP
 * servers).
 */
public final class ToolRegistry {

    // Guarded by this: tools may be registered dynamically and from background threads (e.g. MCP servers connecting in
    // parallel, or reconnecting) while the agent thread reads the enabled set each turn — so all access is
    // synchronized.
    // A LinkedHashMap preserves registration order; the per-turn cost of these short critical sections is negligible.
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> enabledNames = new HashSet<>();
    private final Set<String> builtinNames = new HashSet<>(); // origin, for --no-builtin-tools
    private ToolManifest.Snapshot cachedManifestSnapshot;

    public synchronized void register(Tool tool) {
        tools.put(tool.name(), tool);
        if (tool.defaultEnabled())
            enabledNames.add(tool.name());
        cachedManifestSnapshot = null;
    }

    /** Register a first-party (non-extension) tool; distinguished only so {@code --no-builtin-tools} can target it. */
    public synchronized void registerBuiltin(Tool tool) {
        register(tool);
        builtinNames.add(tool.name());
    }

    /**
     * Recompute the active set from a {@link ToolPolicy} — the one-time bootstrap filter for the {@code --tools} /
     * {@code --exclude-tools} / {@code --no-tools} / {@code --no-builtin-tools} flags. Like {@link #enableOnly} this is
     * a snapshot: tools registered afterwards (e.g. MCP) enable by their own default and aren't retroactively filtered.
     */
    public synchronized void applyPolicy(ToolPolicy policy) {
        enabledNames.clear();
        for (Tool tool : tools.values())
            if (policy.keep(tool.name(), builtinNames.contains(tool.name()), tool.defaultEnabled()))
                enabledNames.add(tool.name());
        cachedManifestSnapshot = null;
    }

    /** Removes a tool (and disables it). No-op if it was never registered. Used for dynamic tools (e.g. MCP). */
    public synchronized void unregister(String name) {
        tools.remove(name);
        enabledNames.remove(name);
        cachedManifestSnapshot = null;
    }

    public synchronized void enableOnly(Collection<String> names) {
        enabledNames.clear();
        enabledNames.addAll(names);
        cachedManifestSnapshot = null;
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
            return Optional.of(tool.execute(toolCallId, new Tool.Args(arguments), sessionCwd));
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
