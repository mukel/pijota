package org.pijota.llm;

import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamResultTest {

    @Test
    void contentInitiallyEmpty() {
        var result = new StreamResult();
        assertEquals("", result.content());
    }

    @Test
    void appendContentAccumulates() {
        var result = new StreamResult();
        result.appendContent("hello");
        result.appendContent(" world");
        assertEquals("hello world", result.content());
    }

    @Test
    void appendNullDoesNothing() {
        var result = new StreamResult();
        result.appendContent("hello");
        result.appendContent(null);
        assertEquals("hello", result.content());
    }

    @Test
    void setContentReplaces() {
        var result = new StreamResult();
        result.appendContent("old");
        result.setContent("new");
        assertEquals("new", result.content());
    }

    @Test
    void setNullContentClears() {
        var result = new StreamResult();
        result.appendContent("old");
        result.setContent(null);
        assertEquals("", result.content());
    }

    @Test
    void thinkingInitiallyEmpty() {
        var result = new StreamResult();
        assertEquals("", result.thinking());
    }

    @Test
    void setThinkingReplaces() {
        var result = new StreamResult();
        result.setThinking("initial");
        result.setThinking("revised");
        assertEquals("revised", result.thinking());
    }

    @Test
    void setNullThinkingClears() {
        var result = new StreamResult();
        result.setThinking("old");
        result.setThinking(null);
        assertEquals("", result.thinking());
    }

    @Test
    void toolCallsDefaultsEmpty() {
        var result = new StreamResult();
        assertTrue(result.toolCalls().isEmpty());
        assertFalse(result.hasToolCalls());
    }

    @Test
    void toolCallsReturnsSetList() {
        var result = new StreamResult();
        var calls = List.of(new ToolCall("id", "read", "{}"));
        result.setToolCalls(calls);
        assertEquals(calls, result.toolCalls());
        assertTrue(result.hasToolCalls());
    }

    @Test
    void toolCallsEmptyListMeansNoToolCalls() {
        var result = new StreamResult();
        result.setToolCalls(List.of());
        assertFalse(result.hasToolCalls());
    }

    @Test
    void usageDefaultsNull() {
        var result = new StreamResult();
        assertNull(result.usage());
    }

    @Test
    void usageStored() {
        var result = new StreamResult();
        var usage = new Usage(42, 10);
        result.setUsage(usage);
        assertEquals(usage, result.usage());
    }

    @Test
    void fullStreamLifecycle() {
        var result = new StreamResult();
        result.appendContent("Hello, ");
        result.appendContent("world.");
        result.setThinking("Let me think...");
        var calls = List.of(new ToolCall("c1", "read", "{}"));
        result.setToolCalls(calls);
        result.setUsage(new Usage(100, 20));

        assertEquals("Hello, world.", result.content());
        assertEquals("Let me think...", result.thinking());
        assertEquals(calls, result.toolCalls());
        assertEquals(100, result.usage().inputTokens());
    }
}
