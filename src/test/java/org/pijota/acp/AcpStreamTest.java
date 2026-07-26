package org.pijota.acp;

import org.pijota.agent.AgentEvent;
import org.pijota.messages.Message;
import org.pijota.messages.Usage;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the AgentEvent → session/update translation in isolation (no connection/session). */
class AcpStreamTest {

    private final AcpStream stream = new AcpStream(() -> 1234L);

    private static Message.Assistant assistant(String content, String thinking) {
        return new Message.Assistant(UUID.randomUUID(), Instant.EPOCH, content, List.of(), thinking, "prov", "model");
    }

    private static String kind(ObjectNode update) {
        return update.get("sessionUpdate").asText();
    }

    @Test
    void messageDeltaBecomesAgentMessageChunk() {
        var out = stream.translate(new AgentEvent.MessageStreamDelta("hello"));
        assertEquals(1, out.size());
        assertEquals("agent_message_chunk", kind(out.get(0)));
        assertEquals("hello", out.get(0).path("content").path("text").asText());
    }

    @Test
    void emptyDeltaEmitsNothing() {
        assertTrue(stream.translate(new AgentEvent.MessageStreamDelta("")).isEmpty());
    }

    @Test
    void thinkingIsDiffedAcrossFrames() {
        // Reasoning arrives as growing full-text frames; we emit only the new suffix each time.
        var first = stream.translate(new AgentEvent.AssistantMessage(assistant("", "Let me"), null));
        assertEquals(1, first.size());
        assertEquals("agent_thought_chunk", kind(first.get(0)));
        assertEquals("Let me", first.get(0).path("content").path("text").asText());

        var second = stream.translate(new AgentEvent.AssistantMessage(assistant("", "Let me think"), null));
        assertEquals(1, second.size());
        assertEquals(" think", second.get(0).path("content").path("text").asText());

        // No new thinking → nothing.
        assertTrue(stream.translate(new AgentEvent.AssistantMessage(assistant("", "Let me think"), null)).isEmpty());
    }

    @Test
    void turnStartResetsThinkingPrefix() {
        stream.translate(new AgentEvent.AssistantMessage(assistant("", "abc"), null));
        stream.translate(new AgentEvent.TurnStart(2));
        // After reset, the same text is treated as brand-new (full text re-emitted).
        var out = stream.translate(new AgentEvent.AssistantMessage(assistant("", "abc"), null));
        assertEquals("abc", out.get(0).path("content").path("text").asText());
    }

    @Test
    void usageEmittedOnlyOnFinalFrame() {
        // usage == null → mid-stream, no usage_update.
        var mid = stream.translate(new AgentEvent.AssistantMessage(assistant("hi", null), null));
        assertTrue(mid.stream().noneMatch(u -> kind(u).equals("usage_update")));

        // usage != null → final frame emits usage_update with the supplied context tokens.
        var fin = stream.translate(new AgentEvent.AssistantMessage(assistant("hi", null), new Usage(10, 5)));
        var usage = fin.stream().filter(u -> kind(u).equals("usage_update")).findFirst().orElseThrow();
        assertEquals(1234L, usage.get("used").asLong());
        assertTrue(usage.get("size").asLong() > 0);
    }

    @Test
    void toolStartBecomesToolCall() {
        var out = stream.translate(new AgentEvent.ToolStart("bash", "call-1", () -> "{\"command\":\"ls\"}"));
        assertEquals(1, out.size());
        assertEquals("tool_call", kind(out.get(0)));
        assertEquals("call-1", out.get(0).get("toolCallId").asText());
        assertEquals("execute", out.get(0).get("kind").asText());
    }

    @Test
    void toolResultBecomesToolCallUpdate() {
        var msg = new Message.ToolResult(UUID.randomUUID(), Instant.EPOCH, "call-1", "bash", "output", false, Map.of());
        var out = stream.translate(new AgentEvent.ToolResult(msg));
        assertEquals(1, out.size());
        assertEquals("tool_call_update", kind(out.get(0)));
        assertEquals("completed", out.get(0).get("status").asText());
    }

    @Test
    void unsurfacedEventsEmitNothing() {
        assertTrue(stream.translate(new AgentEvent.HistoryChanged("load")).isEmpty());
    }
}
