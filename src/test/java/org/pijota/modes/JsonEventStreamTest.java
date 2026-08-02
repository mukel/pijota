package org.pijota.modes;

import org.pijota.agent.AgentEvent;
import org.pijota.messages.Message;
import org.pijota.messages.Usage;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonEventStreamTest {

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final JsonEventStream stream = new JsonEventStream(new PrintStream(sink, true, StandardCharsets.UTF_8));

    private List<JsonNode> emit(AgentEvent... events) {
        for (AgentEvent e : events)
            stream.emit(e);
        return sink.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank())
                                        .map(l -> assertDoesNotThrow(() -> Util.MAPPER.readTree(l), "bad line: " + l))
                                        .toList();
    }

    private static Usage usage(long in, long out) {
        return new Usage(in, out, 0, 0, 0);
    }

    @Test
    void turnBoundariesCarryTheirNumber() {
        var lines = emit(new AgentEvent.TurnStart(1), new AgentEvent.TurnEnd(1));
        assertEquals(List.of("turn_start", "turn_end"), lines.stream().map(n -> n.path("type").asText()).toList());
        assertEquals(1, lines.get(0).path("turn").asInt());
    }

    @Test
    void toolCallsReportArgumentsAndTheirResultUnderOneId() {
        var id = "call-1";
        var result = new Message.ToolResult(UUID.randomUUID(), Instant.now(), id, "bash", "hi\\n", false, Map.of());
        var lines = emit(new AgentEvent.ToolStart("bash", id, () -> "{\"command\":\"echo hi\"}"),
                                        new AgentEvent.BashExecutionUpdate(id, "hi\\n"),
                                        new AgentEvent.ToolResult(result));
        assertEquals("tool_start", lines.get(0).path("type").asText());
        assertEquals("bash", lines.get(0).path("tool").asText());
        assertTrue(lines.get(0).path("arguments").asText().contains("echo hi"), lines.get(0).toString());
        assertEquals("tool_output", lines.get(1).path("type").asText(), "live output, not only the final result");
        assertEquals("tool_result", lines.get(2).path("type").asText());
        assertFalse(lines.get(2).path("isError").asBoolean());
        // A consumer pairs a call with its result by id; without it, concurrent tool calls cannot be attributed.
        for (JsonNode line : lines)
            assertEquals(id, line.path("toolCallId").asText(), line.toString());
    }

    @Test
    void onlyTheSettledAssistantMessageIsEmitted() {
        // While streaming, AssistantMessage repeats with cumulative content; the deltas already carry that. Emitting
        // every one would make a consumer re-render the whole message on each token.
        var partial = new Message.Assistant(UUID.randomUUID(), Instant.now(), "hel", List.of(), null, null, null);
        var settled = new Message.Assistant(UUID.randomUUID(), Instant.now(), "hello", List.of(), null, null, null);
        var lines = emit(new AgentEvent.AssistantMessage(partial, null),
                                        new AgentEvent.AssistantMessage(settled, usage(10, 5)));
        assertEquals(1, lines.size(), "only the message that comes with usage: " + lines);
        assertEquals("hello", lines.get(0).path("content").asText());
        assertEquals(10, lines.get(0).path("usage").path("inputTokens").asLong());
    }

    @Test
    void deltasCarryTheTextSoAConsumerCanRenderProgressively() {
        var lines = emit(new AgentEvent.MessageStreamStart("assistant"), new AgentEvent.MessageStreamDelta("It "),
                                        new AgentEvent.MessageStreamDelta("works"),
                                        new AgentEvent.MessageStreamEnd("assistant"));
        assertEquals(4, lines.size());
        assertEquals("It works", lines.get(1).path("text").asText() + lines.get(2).path("text").asText());
    }

    @Test
    void errorsAreReportedAsAnEventNotSilence() {
        var lines = emit(new AgentEvent.Error(new IllegalStateException("provider refused")));
        assertEquals("error", lines.get(0).path("type").asText());
        assertTrue(lines.get(0).path("message").asText().contains("provider refused"), lines.get(0).toString());
    }

    @Test
    void everyEmittedLineIsTypedAndSelfContained() {
        var lines = emit(new AgentEvent.TurnStart(1), new AgentEvent.HistoryChanged("fork"),
                                        new AgentEvent.Compaction(List.of(), 12, 400, 100, "summary", "threshold"));
        for (JsonNode line : lines)
            assertFalse(line.path("type").asText().isEmpty(), "untyped line: " + line);
        var compaction = lines.getLast();
        assertEquals("threshold", compaction.path("reason").asText());
        assertEquals(12, compaction.path("oldMessageCount").asInt());
    }

    @Test
    void concurrentEmissionNeverInterleavesTwoLines() throws Exception {
        // Events reach the listener on the agent thread while the result is written from the caller's. A line that
        // interleaves with another parses as neither event, which is unrecoverable for a streaming consumer. This
        // pins the property, not the mechanism: it must keep holding if the writing changes.
        int writers = 8, each = 60;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(writers)) {
            for (int w = 0; w < writers; w++) {
                int id = w;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < each; i++)
                        stream.emit(new AgentEvent.MessageStreamDelta("writer-" + id + "-chunk-" + i));
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        var lines = sink.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        assertEquals(writers * each, lines.size(), "no line was lost or split");
        for (String line : lines)
            assertDoesNotThrow(() -> Util.MAPPER.readTree(line), "interleaved write: " + line);
    }
}
