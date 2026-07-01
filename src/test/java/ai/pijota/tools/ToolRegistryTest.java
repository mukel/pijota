package ai.pijota.tools;

import ai.pijota.testkit.TestTools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void enabledToolsRespectsDefaultAndExplicitEnable() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("always", true));
        r.register(TestTools.fake("offline", false));

        var enabled = r.enabledTools();
        assertEquals(1, enabled.size());
        assertEquals("always", enabled.getFirst().name());
    }

    @Test
    void enableOnlyLimitsActiveSet() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("a", true));
        r.register(TestTools.fake("b", true));

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
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        var result = r.execute("tc1", "needsArg", Map.of(), "{}", "/").get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("x"));
    }

    @Test
    void allToolsAndActiveTools() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("a", true));
        r.register(TestTools.fake("b", false));

        assertEquals(2, r.allTools().size());
        assertEquals(1, r.activeTools().size());
        assertEquals("a", r.activeTools().getFirst().name());
    }

    @Test
    void enabledToolNames() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("x", true));
        r.register(TestTools.fake("y", false));

        assertEquals(List.of("x"), r.enabledToolNames());
    }

    @Test
    void getReturnsToolWhenRegistered() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("z", true));

        assertTrue(r.get("z").isPresent());
        assertEquals("z", r.get("z").get().name());
    }

    @Test
    void getReturnsEmptyWhenMissing() {
        var r = new ToolRegistry();
        assertTrue(r.get("missing").isEmpty());
    }

    @Test
    void manifestSnapshotIsCachedUntilRegistryChanges() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("a", true));

        var first = ToolManifest.snapshot(r);
        assertSame(first, ToolManifest.snapshot(r), "unchanged registries reuse the computed manifest");

        r.register(TestTools.fake("b", true));
        var second = ToolManifest.snapshot(r);
        assertNotSame(first, second, "registering a tool invalidates the cache");
        assertTrue(second.json().contains("b"));
    }

    @Test
    void manifestSnapshotInvalidatesWhenToolIsUnregistered() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("a", true));
        r.register(TestTools.fake("b", true));
        var first = ToolManifest.snapshot(r);

        r.unregister("b");
        var second = ToolManifest.snapshot(r);

        assertNotSame(first, second);
        assertTrue(second.json().contains("\"name\":\"a\""));
        assertFalse(second.json().contains("\"name\":\"b\""));
        assertSame(second, ToolManifest.snapshot(r), "new snapshot is cached after unregister");
    }

    @Test
    void manifestSnapshotInvalidatesWhenAllowlistChanges() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("a", true));
        r.register(TestTools.fake("b", true));
        var first = ToolManifest.snapshot(r);

        r.enableOnly(List.of("a"));
        var second = ToolManifest.snapshot(r);

        assertNotSame(first, second);
        assertTrue(second.json().contains("\"name\":\"a\""));
        assertFalse(second.json().contains("\"name\":\"b\""));
    }

    @Test
    void manifestSnapshotExcludesDefaultDisabledToolsUntilEnabled() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("enabled", true));
        r.register(TestTools.fake("disabled", false));

        var first = ToolManifest.snapshot(r);
        assertTrue(first.json().contains("\"name\":\"enabled\""));
        assertFalse(first.json().contains("\"name\":\"disabled\""));

        r.enableOnly(List.of("disabled"));
        var second = ToolManifest.snapshot(r);
        assertFalse(second.json().contains("\"name\":\"enabled\""));
        assertTrue(second.json().contains("\"name\":\"disabled\""));
    }

    @Test
    void manifestSnapshotCanonicalizesSchemaFieldOrderForStableHashes() {
        var schemaOne = Map.<String, Object>of("type", "object", "properties",
                                        Map.of("b", Map.of("type", "string"), "a", Map.of("type", "integer")));
        var schemaTwo = new java.util.LinkedHashMap<String, Object>();
        schemaTwo.put("properties", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "string")));
        schemaTwo.put("type", "object");
        var one = new ToolRegistry();
        var two = new ToolRegistry();
        one.register(new Tool.ToolDefinition("same", "same", schemaOne,
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());
        two.register(new Tool.ToolDefinition("same", "same", schemaTwo,
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());

        assertEquals(ToolManifest.snapshot(one).hash(), ToolManifest.snapshot(two).hash());
        assertEquals(ToolManifest.snapshot(one).json(), ToolManifest.snapshot(two).json());
    }

    @Test
    void manifestSnapshotCanBeExplicitlyInvalidatedForMutableTools() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("a", true));
        var first = ToolManifest.snapshot(r);

        r.invalidateManifestSnapshot();
        var second = ToolManifest.snapshot(r);

        assertNotSame(first, second);
        assertEquals(first.json(), second.json());
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
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
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

    @Test
    void unregisterRemovesAndDisablesTheTool() {
        var r = new ToolRegistry();
        r.register(TestTools.fake("keep", true));
        r.register(TestTools.fake("drop", true));
        assertEquals(2, r.enabledTools().size());

        r.unregister("drop");
        assertTrue(r.get("drop").isEmpty());
        assertFalse(r.enabledToolNames().contains("drop"));
        assertEquals(List.of("keep"), r.enabledToolNames());
        assertDoesNotThrow(() -> r.unregister("never-registered")); // no-op
    }

    @Test
    void policyNoneDisablesAllToolsIncludingBuiltins() {
        var r = new ToolRegistry();
        r.registerBuiltin(TestTools.fake("bash", true));
        r.register(TestTools.fake("task", true));
        r.applyPolicy(new ToolPolicy(ToolPolicy.Scope.NONE, Set.of(), Set.of()));
        assertTrue(r.enabledToolNames().isEmpty(), "--no-tools disables built-in and extension tools alike");
    }

    @Test
    void policyExtensionOnlyDropsBuiltinsButKeepsExtensionTools() {
        var r = new ToolRegistry();
        r.registerBuiltin(TestTools.fake("bash", true));
        r.register(TestTools.fake("task", true));
        r.applyPolicy(new ToolPolicy(ToolPolicy.Scope.EXTENSION_ONLY, Set.of(), Set.of()));
        assertEquals(List.of("task"), r.enabledToolNames());
    }

    @Test
    void policyAllowlistForceEnablesExactlyItsMembers() {
        var r = new ToolRegistry();
        r.registerBuiltin(TestTools.fake("read", true));
        r.register(TestTools.fake("offByDefault", false));
        r.applyPolicy(new ToolPolicy(ToolPolicy.Scope.ALL, Set.of("offByDefault"), Set.of()));
        assertEquals(List.of("offByDefault"), r.enabledToolNames(), "allowlist is authoritative, ignores default-off");
    }

    @Test
    void policyDenylistWinsOverDefaultsAndAllowlist() {
        var r = new ToolRegistry();
        r.registerBuiltin(TestTools.fake("bash", true));
        r.register(TestTools.fake("task", true));
        r.applyPolicy(new ToolPolicy(ToolPolicy.Scope.ALL, Set.of("bash", "task"), Set.of("bash")));
        assertEquals(List.of("task"), r.enabledToolNames());
    }

    @Test
    void concurrentRegistrationLosesNoTools() throws Exception {
        // Reproduces the data race that lost MCP tools when several servers connected on parallel threads:
        // many threads register into the shared registry at once while another thread reads the enabled set.
        var r = new ToolRegistry();
        int n = 500;
        var pool = Executors.newFixedThreadPool(16);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(n);
        var reads = new AtomicBoolean(true);
        // a concurrent reader, to surface read/write races too
        var reader = new Thread(() -> {
            while (reads.get())
                r.enabledTools(); // must never throw / corrupt
        });
        reader.start();
        try {
            for (int i = 0; i < n; i++) {
                String name = "tool-" + i;
                pool.execute(() -> {
                    try {
                        start.await();
                        r.register(TestTools.fake(name, true));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "registrations did not finish");
        } finally {
            reads.set(false);
            reader.join(1000);
            pool.shutdownNow();
        }
        assertEquals(n, r.enabledTools().size(), "every concurrently registered tool must be present");
    }
}
