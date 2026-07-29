package org.pijota.tools;

import org.pijota.llm.ToolSchema;
import org.pijota.util.Util;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The contract between pijota and any LLM-callable tool. Implement directly or use {@link ToolDefinition} to define a
 * tool as a lambda. Every tool exposes a name, description, JSON Schema for arguments, and an async {@link #execute}
 * method. Override {@link #renderResult} and {@link #hasResultView} to customize TUI display; set
 * {@link #executionMode} to {@link ExecutionMode#SEQUENTIAL} for tools that must not run concurrently with others.
 */
public interface Tool {

    String name();

    String description();

    /** JSON Schema for the tool's arguments. */
    Map<String, Object> schema();

    /** Default execute without session cwd. */
    default CompletableFuture<ToolResult> execute(String toolCallId, Args arguments) {
        return execute(toolCallId, arguments, null);
    }

    /** Execute the tool — {@code sessionCwd} is the session's working directory at call time. */
    CompletableFuture<ToolResult> execute(String toolCallId, Args arguments, String sessionCwd);

    /** Execute with optional progress updates. Tools that do not stream progress use the normal execute path. */
    default CompletableFuture<ToolResult> execute(String toolCallId, Args arguments, String sessionCwd,
                                    Consumer<String> onProgress) {
        return execute(toolCallId, arguments, sessionCwd);
    }

    default boolean defaultEnabled() {
        return true;
    }

    /** SEQUENTIAL: tool must run alone; PARALLEL (default): may run concurrently with other tools. */
    enum ExecutionMode {
        SEQUENTIAL, PARALLEL
    }
    default ExecutionMode executionMode() {
        return ExecutionMode.PARALLEL;
    }

    default void abort() {}

    /**
     * A tool whose running OS process can be handed off to the background: the pending tool call completes early with a
     * caller-supplied synthetic result and the process lives on. The turn is never made asynchronous — the call just
     * finishes sooner.
     */
    interface Detachable {
        /**
         * Detaches the currently running call, or empty when nothing is running (or it already finished). The caller
         * adopts the returned process (e.g. into a job registry), re-points its output feed, and MUST then complete the
         * call via {@link DetachedProcess#complete()} so the agent turn resumes.
         */
        Optional<DetachedProcess> detach();
    }

    /**
     * A live process handed off by a detached tool call. {@code redirectOutput} atomically re-points the output feed at
     * the adopter's consumer and returns everything captured before the switch — no chunk is ever lost or duplicated
     * across the hand-off. The adopter observes the eventual exit via {@code process().onExit()} and MUST resolve the
     * still-pending tool call through {@code complete} (which also releases the tool's foreground wait).
     */
    record DetachedProcess(Process process, String command, Function<Consumer<String>, String> redirectOutput,
                                    Consumer<ToolResult> complete) {
    }

    /** Result of a tool execution: content, error flag, and optional structured details. */
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
        CompletableFuture<ToolResult> execute(String toolCallId, Args args, String sessionCwd);
    }

    @FunctionalInterface
    interface ResultRenderer {
        ToolResultView render(Args args, ToolResult result, String cwd);
    }

    /** Lightweight tool registration record — wraps name, description, schema, and an execute lambda. */
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
                public CompletableFuture<ToolResult> execute(String id, Args args, String cwd) {
                    return ToolDefinition.this.execute.execute(id, args, cwd);
                }

                @Override
                public CompletableFuture<ToolResult> execute(String id, Args args, String cwd,
                                                Consumer<String> onProgress) {
                    return execute(id, args, cwd);
                }

                @Override
                public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
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

    /** Minimal tool identity: name and description. */
    record ToolInfo(String name, String description) {
    }

    /**
     * Start defining a tool fluently — the JSON schema is generated from the declared params, so extensions don't
     * hand-build {@code Map.of("type","object",…)}:
     *
     * <pre>{@code
     * Tool.named("hello").description("A simple greeting").glyph("👋").stringParam("name", "Name to greet", true).run((
     *                                 args, cwd) -> Tool.ToolResult.success("Hello, " + args.getString("name") + "!"));
     * }</pre>
     */
    static Builder named(String name) {
        return new Builder(name);
    }

    /** Read-only view over a tool call's arguments with typed accessors — no manual {@code (String) args.get(...)}. */
    record Args(Map<String, Object> map) {
        public String getString(String key) {
            Object v = map.get(key);
            return v == null ? null : v.toString();
        }

        public String getString(String key, String fallback) {
            String v = getString(key);
            return v == null ? fallback : v;
        }

        public boolean getBoolean(String key) {
            Object v = map.get(key);
            return v instanceof Boolean b ? b : "true".equalsIgnoreCase(getString(key));
        }

        public int getInt(String key, int fallback) {
            Object v = map.get(key);
            if (v instanceof Number n)
                return n.intValue();
            try {
                return v == null ? fallback : Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        public long getLong(String key, long fallback) {
            Object v = map.get(key);
            if (v instanceof Number n)
                return n.longValue();
            try {
                return v == null ? fallback : Long.parseLong(v.toString().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        public boolean has(String key) {
            return map.containsKey(key);
        }

        /** Raw argument value (for lists/nested objects the typed accessors don't cover). */
        public Object get(String key) {
            return map.get(key);
        }

        public Object getOrDefault(String key, Object fallback) {
            return map.getOrDefault(key, fallback);
        }
    }

    /** Synchronous tool body — {@link Builder#run} wraps it into a completed future. */
    @FunctionalInterface
    interface SyncExecute {
        ToolResult run(Args args, String sessionCwd);
    }

    /**
     * Fluent {@link Tool} builder: declare params (schema is generated) then finish with {@code run}/{@code runAsync}.
     */
    final class Builder {
        private final String name;
        private String description = "";
        private String glyph = "●";
        private boolean defaultEnabled = true;
        private ResultRenderer resultRenderer;
        private Map<String, Object> rawSchema; // when set, overrides the declared params
        private final ToolSchema.Builder schema = ToolSchema.object();

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder glyph(String glyph) {
            this.glyph = glyph;
            return this;
        }

        public Builder defaultEnabled(boolean defaultEnabled) {
            this.defaultEnabled = defaultEnabled;
            return this;
        }

        public Builder resultRenderer(ResultRenderer resultRenderer) {
            this.resultRenderer = resultRenderer;
            return this;
        }

        /** Use a raw JSON Schema instead of declared params (any {@code *Param} calls are ignored when this is set). */
        public Builder schema(Map<String, Object> rawSchema) {
            this.rawSchema = rawSchema;
            return this;
        }

        public Builder stringParam(String name, String description, boolean required) {
            schema.str(name, description, required);
            return this;
        }

        public Builder boolParam(String name, String description, boolean required) {
            schema.bool(name, description, required);
            return this;
        }

        public Builder intParam(String name, String description, boolean required) {
            schema.integer(name, description, required);
            return this;
        }

        public Builder enumParam(String name, String description, boolean required, List<String> values) {
            schema.enumStr(name, description, required, values);
            return this;
        }

        /** Declares a free-form key→value map (string→string) parameter (e.g. environment variables). */
        public Builder mapParam(String name, String description, boolean required) {
            schema.openObject(name, description, required);
            return this;
        }

        /** Finish with a synchronous body (the common case). */
        public ToolDefinition run(SyncExecute body) {
            return runAsync((id, args, cwd) -> CompletableFuture.completedFuture(body.run(args, cwd)));
        }

        /** Finish with a full async body — for streaming or long-running tools. */
        public ToolDefinition runAsync(Execute body) {
            return new ToolDefinition(name, description, rawSchema != null ? rawSchema : schema.build(), body,
                                            defaultEnabled, resultRenderer, glyph);
        }
    }

    /**
     * Rendered view of a tool result for TUI display.
     * 
     * @param summary
     *            single-line summary shown after the tool name + args
     * @param body
     *            additional lines rendered below the summary (diff, output preview, etc.)
     */
    record ToolResultView(String summary, List<String> body) {
        public static ToolResultView of(String summary) {
            return new ToolResultView(summary, List.of());
        }

        public static ToolResultView of(String summary, List<String> body) {
            return new ToolResultView(summary, body);
        }
    }

    /**
     * Produce a display-oriented rendering of this tool's result. Each tool can override to provide richer output
     * (diffs, previews, etc.).
     */
    default ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        String text = result.content();
        if (result.isError()) {
            return ToolResultView.of(text != null ? text : "");
        }
        return ToolResultView.of(Util.firstLine(text));
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
