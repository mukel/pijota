package ai.pijota.extensions.mcp;

import ai.pijota.tools.Tool;

import java.time.Duration;
import java.util.Map;

/**
 * Turns an {@link McpToolDescriptor} into a pijota {@link Tool.ToolDefinition}: it namespaces and sanitizes the name,
 * passes the JSON-Schema through (with a permissive fallback), and wraps {@code tools/call} so the execute lambda maps
 * the client's {@link McpCallResult} to a {@code ToolResult} (bounding its size). All call failures (timeout, crash,
 * protocol error) are turned into {@code ToolResult.error} so a turn never hangs.
 */
final class McpToolBridge {

    /** Provider function-name caps are commonly ~64 chars; stay safely under. */
    static final int MAX_NAME = 64;
    /** Bound tool output so a chatty server can't blow out the context window. */
    static final int MAX_CONTENT = 100_000;

    private McpToolBridge() {}

    static Tool.ToolDefinition toDefinition(String serverName, McpToolDescriptor descriptor, McpClient client,
                                    Duration callTimeout) {
        String name = namespacedName(serverName, descriptor.name());
        String description = descriptor.description() == null ? "(no description)" : descriptor.description();
        Map<String, Object> schema = normalizeSchema(descriptor.inputSchema());
        String originalName = descriptor.name();
        Tool.Execute execute = (id, args, cwd) -> client.callTool(originalName, args.map(), callTimeout)
                                        .thenApply(McpToolBridge::toToolResult)
                                        .exceptionally(ex -> Tool.ToolResult.error("mcp: " + rootMessage(ex)));
        return new Tool.ToolDefinition(name, description, schema, execute);
    }

    /** {@code mcp__<server>__<tool>}, sanitized to {@code [A-Za-z0-9_-]} and hash-suffixed if it overflows the cap. */
    static String namespacedName(String serverName, String toolName) {
        String full = "mcp__" + sanitize(serverName) + "__" + sanitize(toolName);
        if (full.length() <= MAX_NAME)
            return full;
        String suffix = "_" + Integer.toHexString(full.hashCode());
        return full.substring(0, MAX_NAME - suffix.length()) + suffix;
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank())
            return "x";
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * Pass the MCP {@code inputSchema} straight through (pijota's {@code ToolSchema} converter reads the standard
     * keywords). If it's missing or not an object schema, fall back to a permissive object so the model can still call
     * the tool.
     */
    static Map<String, Object> normalizeSchema(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty())
            return Map.of("type", "object");
        Object type = inputSchema.get("type");
        if (!"object".equals(type))
            return Map.of("type", "object");
        return inputSchema;
    }

    /**
     * Wraps an {@link McpCallResult} as a pijota {@code ToolResult}, bounding the text to protect the context window.
     */
    static Tool.ToolResult toToolResult(McpCallResult result) {
        String full = result.text() == null ? "" : result.text();
        String text = full.length() > MAX_CONTENT ? full.substring(0, MAX_CONTENT) + "\n…[truncated]" : full;
        return result.isError() ? Tool.ToolResult.error(text) : Tool.ToolResult.success(text);
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause)
            cause = cause.getCause();
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}
