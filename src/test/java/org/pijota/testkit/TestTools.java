package org.pijota.testkit;

import org.pijota.tools.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Factories for lightweight {@link Tool}s used to wire up registry / executor / agent-loop / extension tests, in place
 * of the ad-hoc {@code Tool.ToolDefinition} definitions each test would otherwise hand-roll. These cover the common
 * assertion-agnostic cases; tests that need to track invocation order or concurrency should still define their own
 * stateful tool inline.
 */
public final class TestTools {
    private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object", "properties", Map.of());

    private TestTools() {}

    /** A tool that always succeeds with {@code "ok"}, with the given default-enabled flag. */
    public static Tool fake(String name, boolean defaultEnabled) {
        return new Tool.ToolDefinition(name, name + " desc", EMPTY_SCHEMA,
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")),
                                        defaultEnabled).toTool();
    }

    /**
     * A tool that echoes its {@code "value"} argument back as {@code "tool:<value>"} — for agent/executor tests that
     * need to assert the tool actually received the model's arguments. Schema: one optional string {@code value}.
     */
    public static Tool echoing(String name) {
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of("value", Map.of("type", "string")));
        return new Tool.ToolDefinition(name, name + " desc", schema,
                                        (id, args, cwd) -> CompletableFuture.completedFuture(Tool.ToolResult
                                                                        .success("tool:" + args.get("value"))))
                                        .toTool();
    }
}
