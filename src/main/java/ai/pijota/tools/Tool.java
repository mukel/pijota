package ai.pijota.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface Tool {

    String name();

    String description();

    Map<String, Object> schema();

    default CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> arguments) {
        return execute(toolCallId, arguments, null);
    }

    CompletableFuture<ToolResult> execute(
            String toolCallId, Map<String, Object> arguments, String sessionCwd);

    default boolean defaultEnabled() { return true; }

    enum ExecutionMode { SEQUENTIAL, PARALLEL }
    default ExecutionMode executionMode() { return ExecutionMode.PARALLEL; }
    default void abort() {}

    record ToolResult(String content, boolean isError, Map<String, Object> details) {
        public static ToolResult success(String content) {
            return new ToolResult(content, false, Map.of());
        }

        public static ToolResult success(String content, Map<String, Object> details) {
            return new ToolResult(content, false, details == null ? Map.of() : Map.copyOf(details));
        }

        public static ToolResult error(String content) {
            return new ToolResult(content, true, Map.of());
        }
    }

    @FunctionalInterface
    interface Execute {
        CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String sessionCwd);
    }

    record ToolDefinition(
        String name,
        String description,
        Map<String, Object> schema,
        Execute execute,
        boolean defaultEnabled
    ) {
        public ToolDefinition(String name, String description, Map<String, Object> schema,
                              Execute execute) {
            this(name, description, schema, execute, true);
        }

        public Tool toTool() {
            return new Tool() {
                @Override public String name() { return name; }
                @Override public String description() { return description; }
                @Override public Map<String, Object> schema() { return schema; }
                @Override public boolean defaultEnabled() { return defaultEnabled; }
                @Override public CompletableFuture<ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                    return ToolDefinition.this.execute.execute(id, args, cwd);
                }
            };
        }
    }

    record ToolInfo(String name, String description) {}
}
