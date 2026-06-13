package ai.pijota.extensions.mcp;

import ai.pijota.tools.Tool;
import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolBridgeTest {

    private static JsonNode json(String s) {
        try {
            return Util.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
    void mapsTextContentAndErrorFlag() {
        var ok = McpToolBridge.toResult(json("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}"));
        assertFalse(ok.isError());
        assertEquals("hello", ok.content());

        var err = McpToolBridge.toResult(
                                        json("{\"content\":[{\"type\":\"text\",\"text\":\"boom\"}],\"isError\":true}"));
        assertTrue(err.isError());
        assertEquals("boom", err.content());
    }

    @Test
    void summarizesNonTextContent() {
        var r = McpToolBridge.toResult(json("{\"content\":[{\"type\":\"text\",\"text\":\"a\"},"
                                        + "{\"type\":\"image\",\"data\":\"...\"}]}"));
        assertEquals("a\n[image omitted]", r.content());
    }

    @Test
    void truncatesHugeOutput() {
        String big = "x".repeat(McpToolBridge.MAX_CONTENT + 1000);
        var r = McpToolBridge.toResult(json("{\"content\":[{\"type\":\"text\",\"text\":\"" + big + "\"}]}"));
        assertTrue(r.content().length() <= McpToolBridge.MAX_CONTENT + 32, "should be bounded");
        assertTrue(r.content().endsWith("[truncated]"), r.content().substring(r.content().length() - 20));
    }

    @Test
    void buildsAToolDefinitionWithNamespacedName() {
        var desc = new McpToolDescriptor("echo", "Echo a string", Map.of("type", "object"));
        Tool.ToolDefinition def = McpToolBridge.toDefinition("srv", desc, null, java.time.Duration.ofSeconds(1));
        assertEquals("mcp__srv__echo", def.name());
        assertEquals("Echo a string", def.description());
    }
}
