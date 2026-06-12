package ai.pijota.extensions.task;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentTest {

    @Test
    void recordStoresFields() {
        var agent = new SubAgent("test", "desc", "prompt", List.of("read", "write"), "gpt-4", "project");
        assertEquals("test", agent.name());
        assertEquals("desc", agent.description());
        assertEquals("prompt", agent.systemPrompt());
        assertEquals(List.of("read", "write"), agent.tools());
        assertEquals("gpt-4", agent.model());
        assertEquals("project", agent.source());
    }

    @Test
    void recordEquality() {
        var a = new SubAgent("n", "d", "s", List.of(), null, "src");
        var b = new SubAgent("n", "d", "s", List.of(), null, "src");
        assertEquals(a, b);
        assertNotEquals(a, new SubAgent("x", "d", "s", List.of(), null, "src"));
    }

    @Test
    void toolsFromMapWithList() {
        var tools = SubAgent.toolsFromMap(List.of("read", "write"));
        assertEquals(List.of("read", "write"), tools);
    }

    @Test
    void toolsFromMapWithCommaSeparatedString() {
        var tools = SubAgent.toolsFromMap("read, write , edit");
        assertEquals(3, tools.size());
        assertTrue(tools.contains("read"));
        assertTrue(tools.contains("write"));
        assertTrue(tools.contains("edit"));
    }

    @Test
    void toolsFromMapWithSemicolonSeparated() {
        var tools = SubAgent.toolsFromMap("read;write;glob");
        assertEquals(3, tools.size());
    }

    @Test
    void toolsFromMapWithNull() {
        assertTrue(SubAgent.toolsFromMap(null).isEmpty());
    }

    @Test
    void toolsFromMapWithEmptyList() {
        assertTrue(SubAgent.toolsFromMap(List.of()).isEmpty());
    }

    @Test
    void toolsFromMapWithBlankString() {
        var tools = SubAgent.toolsFromMap("read,  , write");
        assertEquals(2, tools.size());
    }

    @Test
    void stringHelperWithString() {
        assertEquals("value", SubAgent.string(Map.of("key", "value"), "key", "default"));
    }

    @Test
    void stringHelperWithBlankString() {
        assertEquals("default", SubAgent.string(Map.of("key", "  "), "key", "default"));
    }

    @Test
    void stringHelperWithNonString() {
        assertEquals("default", SubAgent.string(Map.of("key", 42), "key", "default"));
    }

    @Test
    void stringHelperWithMissingKey() {
        assertEquals("fallback", SubAgent.string(Map.of(), "key", "fallback"));
    }
}
