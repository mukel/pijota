package ai.pijota.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

        r.enableOnly(List.of("a"));

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
                                                                        "required", List.of("x")),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(
                                                                        Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "needsArg", Map.of(), "{}", "/").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("x"));
    }

    @Test
    void allToolsAndActiveTools() {
        var r = new ToolRegistry();
        r.register(fakeTool("a", true));
        r.register(fakeTool("b", false));

        assertEquals(2, r.allTools().size());
        assertEquals(1, r.activeTools().size());
        assertEquals("a", r.activeTools().getFirst().name());
    }

    @Test
    void enabledToolNames() {
        var r = new ToolRegistry();
        r.register(fakeTool("x", true));
        r.register(fakeTool("y", false));

        assertEquals(List.of("x"), r.enabledToolNames());
    }

    @Test
    void getReturnsToolWhenRegistered() {
        var r = new ToolRegistry();
        r.register(fakeTool("z", true));

        assertTrue(r.get("z").isPresent());
        assertEquals("z", r.get("z").get().name());
    }

    @Test
    void getReturnsEmptyWhenMissing() {
        var r = new ToolRegistry();
        assertTrue(r.get("missing").isEmpty());
    }

    @Test
    void abortRunningToolsCallsAbort() {
        var r = new ToolRegistry();
        var aborted = new AtomicBoolean();
        r.register(new Tool() {
            @Override
            public String name() {
                return "abortable";
            }

            @Override
            public String description() {
                return "aborts";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                return CompletableFuture.completedFuture(Tool.ToolResult.success("ok"));
            }

            @Override
            public void abort() {
                aborted.set(true);
            }
        });

        r.abortRunningTools();

        assertTrue(aborted.get());
    }

    @Test
    void validateStringTypeError() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("typed", "typed",
                                        Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")),
                                                                        "required", List.of("x")),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "typed", Map.of("x", 123), "{}", "/").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("expected string"));
    }

    @Test
    void validateIntegerTypeError() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("typed", "typed",
                                        Map.of("type", "object", "properties", Map.of("n", Map.of("type", "integer"))),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "typed", Map.of("n", "not a number"), "{}", "/").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("expected integer"));
    }

    @Test
    void validateBooleanTypeError() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("typed", "typed",
                                        Map.of("type", "object", "properties",
                                                                        Map.of("flag", Map.of("type", "boolean"))),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "typed", Map.of("flag", "true"), "{}", "/").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("expected boolean"));
    }

    @Test
    void validateArrayTypeError() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("typed", "typed",
                                        Map.of("type", "object", "properties",
                                                                        Map.of("items", Map.of("type", "array"))),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "typed", Map.of("items", "not array"), "{}", "/").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("expected array"));
    }

    @Test
    void pathAliasSatisfiesRequired() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("needsPath", "needs path",
                                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")),
                                                                        "required", List.of("path")),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "needsPath", Map.of("filePath", "/tmp/f"), "{}", "/").get(1, TimeUnit.SECONDS);

        assertFalse(result.isError());
    }

    @Test
    void editsAliasSatisfiesRequired() throws Exception {
        var r = new ToolRegistry();
        r.register(new Tool.ToolDefinition("needsEdits", "needs edits",
                                        Map.of("type", "object", "properties", Map.of("edits", Map.of("type", "array")),
                                                                        "required", List.of("edits")),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "needsEdits", Map.of("oldText", "a", "newText", "b"), "{}", "/").get(1,
                                        TimeUnit.SECONDS);

        assertFalse(result.isError());
    }

    private static Tool fakeTool(String name, boolean defaultEnabled) {
        return new Tool.ToolDefinition(name, name + " desc", Map.of("type", "object", "properties", Map.of()),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(
                                                                        Tool.ToolResult.success("ok")),
                                        defaultEnabled).toTool();
    }
}
