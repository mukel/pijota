package ai.pijota.testkit;

import ai.pijota.tools.Tool;

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
}
