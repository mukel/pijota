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

    CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> arguments, String sessionCwd);

    default boolean defaultEnabled() {
        return true;
    }

    enum ExecutionMode {
        SEQUENTIAL, PARALLEL
    }
    default ExecutionMode executionMode() {
        return ExecutionMode.PARALLEL;
    }

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

    @FunctionalInterface
    interface ResultRenderer {
        ToolResultView render(Map<String, Object> args, ToolResult result, String cwd);
    }

    record ToolDefinition(String name, String description, Map<String, Object> schema, Execute execute,
                                    boolean defaultEnabled, ResultRenderer resultRenderer, String glyph) {
        public ToolDefinition(String name, String description, Map<String, Object> schema, Execute execute) {
            this(name, description, schema, execute, true, null, "●");
        }

        public ToolDefinition(String name, String description, Map<String, Object> schema, Execute execute,
                                        ResultRenderer resultRenderer) {
            this(name, description, schema, execute, true, resultRenderer, "●");
        }

        public ToolDefinition(String name, String description, Map<String, Object> schema, Execute execute,
                                        boolean defaultEnabled) {
            this(name, description, schema, execute, defaultEnabled, null, "●");
        }

        /** Returns a copy with the given display glyph (the marker shown before the tool in the TUI). */
        public ToolDefinition withGlyph(String glyph) {
            return new ToolDefinition(name, description, schema, execute, defaultEnabled, resultRenderer, glyph);
        }

        /** Adapts a full {@link Tool} into a definition — the inverse of {@link #toTool()}. */
        public static ToolDefinition from(Tool tool) {
            return new ToolDefinition(tool.name(), tool.description(), tool.schema(),
                                            (id, args, cwd) -> tool.execute(id, args, cwd), tool.defaultEnabled(),
                                            tool::renderResult, tool.glyph());
        }

        public Tool toTool() {
            return new Tool() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public String description() {
                    return description;
                }

                @Override
                public Map<String, Object> schema() {
                    return schema;
                }

                @Override
                public boolean defaultEnabled() {
                    return defaultEnabled;
                }

                @Override
                public CompletableFuture<ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                    return ToolDefinition.this.execute.execute(id, args, cwd);
                }

                @Override
                public ToolResultView renderResult(Map<String, Object> args, ToolResult result, String cwd) {
                    return resultRenderer != null
                                                    ? resultRenderer.render(args, result, cwd)
                                                    : Tool.super.renderResult(args, result, cwd);
                }

                @Override
                public boolean hasResultView() {
                    return resultRenderer != null;
                }

                @Override
                public String glyph() {
                    return glyph;
                }
            };
        }
    }

    record ToolInfo(String name, String description) {
    }

    /**
     * Rendered view of a tool result for TUI display.
     * 
     * @param summary
     *            single-line summary shown after the tool name + args
     * @param body
     *            additional lines rendered below the summary (diff, output preview, etc.)
     */
    record ToolResultView(String summary, java.util.List<String> body) {
        public static ToolResultView of(String summary) {
            return new ToolResultView(summary, java.util.List.of());
        }

        public static ToolResultView of(String summary, java.util.List<String> body) {
            return new ToolResultView(summary, body);
        }
    }

    /**
     * Produce a display-oriented rendering of this tool's result. Each tool can override to provide richer output
     * (diffs, previews, etc.).
     */
    default ToolResultView renderResult(Map<String, Object> args, ToolResult result, String cwd) {
        String text = result.content();
        if (result.isError()) {
            return ToolResultView.of(text != null ? text : "");
        }
        if (text == null || text.isBlank())
            return ToolResultView.of("");
        String first = text.lines().findFirst().orElse(text);
        return ToolResultView.of(first);
    }

    /**
     * Whether {@link #renderResult} produces a meaningful custom view (vs. the generic default). The TUI uses this to
     * decide which tools get their own rendering, without hardcoding tool names.
     */
    default boolean hasResultView() {
        return false;
    }

    /** The display glyph (emoji or character) shown before this tool in the TUI. Default is a neutral bullet. */
    default String glyph() {
        return "●";
    }
}
