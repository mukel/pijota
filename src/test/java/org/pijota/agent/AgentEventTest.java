package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.Usage;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTest {

    @Test
    void turnStartStoresNumber() {
        var event = new AgentEvent.TurnStart(1);
        assertEquals(1, event.turn());
    }

    @Test
    void turnEndStoresNumber() {
        var event = new AgentEvent.TurnEnd(3);
        assertEquals(3, event.turn());
    }

    @Test
    void assistantMessageStoresMessageAndUsage() {
        var msg = new Message.Assistant("hello", List.of(), "think");
        var usage = new Usage(100, 50);
        var event = new AgentEvent.AssistantMessage(msg, usage);
        assertEquals(msg, event.message());
        assertEquals(usage, event.usage());
    }

    @Test
    void assistantMessageNullUsage() {
        var msg = new Message.Assistant("hello", List.of(), "think");
        var event = new AgentEvent.AssistantMessage(msg, null);
        assertNull(event.usage());
    }

    @Test
    void toolStartStoresMetadata() {
        var event = new AgentEvent.ToolStart("read", "call-1", () -> "{\"file\":\"x.txt\"}");
        assertEquals("read", event.toolName());
        assertEquals("call-1", event.toolCallId());
        assertEquals("{\"file\":\"x.txt\"}", event.argumentsSupplier().get());
    }

    @Test
    void toolResultStoresMessage() {
        var msg = new Message.ToolResult("call-1", "read", "content", false);
        var event = new AgentEvent.ToolResult(msg);
        assertEquals(msg, event.message());
    }

    @Test
    void messageStreamStartStoresType() {
        var event = new AgentEvent.MessageStreamStart("assistant");
        assertEquals("assistant", event.messageType());
    }

    @Test
    void messageStreamDeltaStoresText() {
        var event = new AgentEvent.MessageStreamDelta("some delta");
        assertEquals("some delta", event.delta());
    }

    @Test
    void messageStreamEndStoresType() {
        var event = new AgentEvent.MessageStreamEnd("tool_call");
        assertEquals("tool_call", event.messageType());
    }

    @Test
    void compactionStreamStoresContentAndThinking() {
        var event = new AgentEvent.CompactionStream("summary text", "thinking text");
        assertEquals("summary text", event.content());
        assertEquals("thinking text", event.thinking());
    }

    @Test
    void compactionStreamEndIsEmpty() {
        var event = new AgentEvent.CompactionStreamEnd();
        assertNotNull(event);
    }

    @Test
    void compactionStoresAllFields() {
        var msgs = List.<Message>of(new Message.User("hi"));
        var event = new AgentEvent.Compaction(msgs, 5, 1000, 200, "summary", "context window full");
        assertEquals(msgs, event.compactedMessages());
        assertEquals(5, event.oldCount());
        assertEquals(1000, event.estimatedChars());
        assertEquals(200, event.estimatedTokens());
        assertEquals("summary", event.summary());
        assertEquals("context window full", event.reason());
    }

    @Test
    void customMessageStoresMessage() {
        var msg = new Message.Custom("type", "content");
        var event = new AgentEvent.CustomMessage(msg);
        assertEquals(msg, event.message());
    }

    @Test
    void errorStoresThrowable() {
        var ex = new RuntimeException("boom");
        var event = new AgentEvent.Error(ex);
        assertEquals(ex, event.throwable());
        assertEquals("boom", event.throwable().getMessage());
    }

    @Test
    void sealedHierarchyCoversAllCases() {
        assertTrue(new AgentEvent.TurnStart(1) instanceof AgentEvent);
        assertTrue(new AgentEvent.TurnEnd(1) instanceof AgentEvent);
        assertTrue(new AgentEvent.AssistantMessage(new Message.Assistant("x", List.of(), "t"),
                                        null) instanceof AgentEvent);
        assertTrue(new AgentEvent.ToolStart("x", "id", () -> "{}") instanceof AgentEvent);
        assertTrue(new AgentEvent.ToolResult(new Message.ToolResult("id", "n", "c", false)) instanceof AgentEvent);
        assertTrue(new AgentEvent.MessageStreamStart("x") instanceof AgentEvent);
        assertTrue(new AgentEvent.MessageStreamDelta("x") instanceof AgentEvent);
        assertTrue(new AgentEvent.MessageStreamEnd("x") instanceof AgentEvent);
        assertTrue(new AgentEvent.CompactionStream("x", "y") instanceof AgentEvent);
        assertTrue(new AgentEvent.CompactionStreamEnd() instanceof AgentEvent);
        assertTrue(new AgentEvent.Compaction(List.of(), 0, 0, 0, "", "") instanceof AgentEvent);
        assertTrue(new AgentEvent.CustomMessage(new Message.Custom("t", "c")) instanceof AgentEvent);
        assertTrue(new AgentEvent.Error(new RuntimeException()) instanceof AgentEvent);
    }
}
