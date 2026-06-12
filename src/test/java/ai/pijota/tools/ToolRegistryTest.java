package ai.pijota.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void enabledToolsRespectsDefaultAndExplicitEnable() {
        var r = new ToolRegistry();
        r.register(fakeTool("always", true));
        r.register(fakeTool("offline", false));

        var enabled = r.enabledTools();
        assertEquals(1, enabled.size());
        assertEquals("always", enabled.getFirst().name());
    }

    @Test
    void enableOnlyLimitsActiveSet() {
        var r = new ToolRegistry();
        r.register(fakeTool("a", true));
        r.register(fakeTool("b", true));

        r.enableOnly(java.util.List.of("a"));

        assertEquals(1, r.enabledTools().size());
        assertEquals("a", r.enabledTools().getFirst().name());
    }

    @Test
    void executeReturnsErrorForUnknownTool() throws Exception {
        var r = new ToolRegistry();
        var result = r.execute("tc1", "unknown", Map.of(), "{}", "/tmp").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown tool"));
    }

    @Test
    void executeReturnsSuccessForRegisteredTool() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("echo", "Echo tool",
                                        Map.of("type", "object", "properties", Map.of("msg", Map.of("type", "string"))),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(Tool.ToolResult
                                                                        .success("echo: " + args.get("msg"))))
                                        .toTool());

        var result = r.execute("tc1", "echo", Map.of("msg", (Object) "hello"), "{\"msg\":\"hello\"}", "/").get(1,
                                        TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertEquals("echo: hello", result.content());
    }

    @Test
    void executeValidatesRequiredParameters() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("needsArg", "Needs arg",
                                        Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")),
                                                                        "required", java.util.List.of("x")),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "needsArg", Map.of(), "{}", "/").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("x"));
    }

    private static Tool fakeTool(String name, boolean defaultEnabled) {
        return new Tool.ToolDefinition(name, name + " desc", Map.of("type", "object", "properties", Map.of()),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")),
                                        defaultEnabled).toTool();
    }
}
