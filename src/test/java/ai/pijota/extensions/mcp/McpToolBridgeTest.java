package ai.pijota.extensions.mcp;

import ai.pijota.tools.Tool;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolBridgeTest {

    @Test
    void namespacesAndSanitizesNames() {
        assertEquals("mcp__github__create_issue", McpToolBridge.namespacedName("github", "create_issue"));
        // illegal chars in either segment become underscores
        assertEquals("mcp__my_srv__do_it", McpToolBridge.namespacedName("my srv", "do.it"));
    }

    @Test
    void longNamesAreHashTruncatedUnderTheCap() {
        String name = McpToolBridge.namespacedName("a".repeat(50), "b".repeat(50));
        assertTrue(name.length() <= McpToolBridge.MAX_NAME, name);
        assertTrue(name.startsWith("mcp__"), name);
    }

    @Test
    void schemaPassesThroughForObjectSchemasAndFallsBackOtherwise() {
        var object = Map.<String, Object>of("type", "object", "properties", Map.of("x", Map.of("type", "string")));
        assertSame(object, McpToolBridge.normalizeSchema(object));

        assertEquals(Map.of("type", "object"), McpToolBridge.normalizeSchema(null));
        assertEquals(Map.of("type", "object"), McpToolBridge.normalizeSchema(Map.of()));
        // a non-object root (e.g. a bare string schema) is not usable as tool params → permissive fallback
        assertEquals(Map.of("type", "object"), McpToolBridge.normalizeSchema(Map.of("type", "string")));
    }

    @Test
    void wrapsTextAndErrorFlag() {
        var ok = McpToolBridge.toToolResult(new McpCallResult("hello", false));
        assertFalse(ok.isError());
        assertEquals("hello", ok.content());

        var err = McpToolBridge.toToolResult(new McpCallResult("boom", true));
        assertTrue(err.isError());
        assertEquals("boom", err.content());
    }

    @Test
    void truncatesHugeOutput() {
        var r = McpToolBridge.toToolResult(new McpCallResult("x".repeat(McpToolBridge.MAX_CONTENT + 1000), false));
        assertTrue(r.content().length() <= McpToolBridge.MAX_CONTENT + 32, "should be bounded");
        assertTrue(r.content().endsWith("[truncated]"), r.content().substring(r.content().length() - 20));
    }

    @Test
    void buildsAToolDefinitionWithNamespacedName() {
        var desc = new McpToolDescriptor("echo", "Echo a string", Map.of("type", "object"));
        Tool.ToolDefinition def = McpToolBridge.toDefinition("srv", desc, null, Duration.ofSeconds(1));
        assertEquals("mcp__srv__echo", def.name());
        assertEquals("Echo a string", def.description());
    }

    @Test
    void nullDescriptionGetsAPlaceholder() {
        var def = McpToolBridge.toDefinition("srv", new McpToolDescriptor("t", null, Map.of("type", "object")), null,
                                        Duration.ofSeconds(1));
        assertEquals("(no description)", def.description());
    }

    @Test
    void wrapsNullTextAsEmpty() {
        assertEquals("", McpToolBridge.toToolResult(new McpCallResult(null, false)).content());
    }

    @Test
    void blankNameSegmentsFallBack() {
        assertEquals("mcp__x__x", McpToolBridge.namespacedName("", ""));
        assertEquals("mcp__x__echo", McpToolBridge.namespacedName(null, "echo"));
    }

    @Test
    void executeBridgesThroughTheClientOnSuccess() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()),
                                        (t, a) -> FakeMcpTransport.Outcome.result("pong:" + a.path("v").asText("")));
        var client = new McpClient("srv", transport, Runnable::run);
        client.connect(Duration.ofSeconds(2));
        try {
            var def = McpToolBridge.toDefinition("srv", new McpToolDescriptor("echo", "Echo", Map.of("type", "object")),
                                            client, Duration.ofSeconds(2));
            Tool.ToolResult result = def.execute().execute("id-1", new Tool.Args(Map.of("v", "hi")), null).get(2,
                                            TimeUnit.SECONDS);
            assertFalse(result.isError());
            assertEquals("pong:hi", result.content());
        } finally {
            client.close();
        }
    }

    @Test
    void executeMapsCallFailureToAToolError() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.hang());
        var client = new McpClient("srv", transport, Runnable::run);
        client.connect(Duration.ofSeconds(2));
        try {
            // short call timeout so the never-answered call maps to a ToolResult.error rather than throwing
            var def = McpToolBridge.toDefinition("srv", new McpToolDescriptor("hang", "Hang", Map.of("type", "object")),
                                            client, Duration.ofMillis(150));
            Tool.ToolResult result = def.execute().execute("id-1", new Tool.Args(Map.of()), null).get(2,
                                            TimeUnit.SECONDS);
            assertTrue(result.isError());
            assertTrue(result.content().startsWith("mcp:"), result.content());
        } finally {
            client.close();
        }
    }
}
