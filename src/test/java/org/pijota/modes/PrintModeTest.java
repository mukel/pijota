package org.pijota.modes;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.agent.AgentLoop;
import org.pijota.cli.Args;
import org.pijota.testkit.FauxLlm;
import org.pijota.tools.ToolRegistry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrintModeTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();

    @BeforeEach
    void captureStreams() {
        System.setOut(new PrintStream(capturedOut));
        System.setErr(new PrintStream(capturedErr));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private AgentLoop loopReturning(String content) {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content(content));
        return AgentLoop.builder(llm, new ToolRegistry(), "/tmp").build();
    }

    @Test
    void textModePrintsAssistantContent() {
        var loop = loopReturning("hello world");
        int code = PrintMode.run("hi", loop, Args.OutputMode.TEXT);
        assertEquals(0, code);
        // No newline is printed when usage is null.
        assertEquals("hello world", capturedOut.toString());
    }

    @Test
    void textModeDeltasDoNotReprintCommonPrefix() {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.thinkingAndContent(List.of(), "Hello", " world"));
        var loop = AgentLoop.builder(llm, new ToolRegistry(), "/tmp").build();
        int code = PrintMode.run("hi", loop, Args.OutputMode.TEXT);
        assertEquals(0, code);
        assertEquals("Hello world", capturedOut.toString());
    }

    /** Every non-blank line of the stream, parsed. Fails the test if any line is not valid JSON. */
    private List<com.fasterxml.jackson.databind.JsonNode> lines() {
        return capturedOut.toString().lines().filter(l -> !l.isBlank())
                                        .map(l -> assertDoesNotThrow(() -> org.pijota.util.Util.MAPPER.readTree(l),
                                                                        "not valid JSON: " + l))
                                        .toList();
    }

    @Test
    void jsonModeReportsTheModelThatAnsweredAndWhatItUsed() {
        var loop = loopReturning("hello world");
        int code = PrintMode.run("hi", loop, Args.OutputMode.JSON);
        assertEquals(0, code);
        var result = lines().getLast();
        assertEquals("result", result.path("type").asText(), "the last line is the run's outcome");
        assertEquals("hello world", result.path("messages").get(0).path("content").asText());
        // A script driving pijota must be able to attribute and budget the run. "unknown" was hardcoded here.
        assertNotEquals("unknown", result.path("model").asText(), "the answering model is named");
        assertTrue(result.hasNonNull("provider") && result.hasNonNull("model"), result.toString());
        assertTrue(result.path("usage").has("inputTokens"), "usage is reported: " + result);
    }

    @Test
    void jsonModeStreamsEventsAsTheyHappenNotOnlyAtTheEnd() {
        // The point of the mode: a wrapper UI or CI annotator follows the run live. One trailing blob cannot do that.
        var loop = loopReturning("hello world");
        assertEquals(0, PrintMode.run("hi", loop, Args.OutputMode.JSON));
        var types = lines().stream().map(n -> n.path("type").asText()).toList();
        assertTrue(types.size() > 1, "expected an event stream, got a single line: " + capturedOut);
        assertTrue(types.contains("turn_start"), "turn boundaries are reported: " + types);
        assertEquals("result", types.getLast(), "result is last so a consumer knows when to stop: " + types);
        assertTrue(types.stream().noneMatch(String::isEmpty), "every line is typed: " + types);
    }

    @Test
    void everyLineIsValidJsonEvenWhenTheAnswerContainsQuotesAndNewlines() {
        // Hand-built JSON used to escape this by hand; a mapper makes malformed output impossible. Checked per line:
        // parsing the whole output stops at the first document and would miss a broken one later.
        var loop = loopReturning("say \"hi\"\nand a \\ backslash\ttab");
        assertEquals(0, PrintMode.run("hi", loop, Args.OutputMode.JSON));
        var result = lines().getLast();
        assertEquals("say \"hi\"\nand a \\ backslash\ttab", result.path("messages").get(0).path("content").asText(),
                                        "content survives the round trip intact");
    }

    @Test
    void failureReturnsNonZeroAndPrintsError() {
        var llm = new FauxLlm(); // no queued response => failed future
        var loop = AgentLoop.builder(llm, new ToolRegistry(), "/tmp").build();
        int code = PrintMode.run("hi", loop, Args.OutputMode.TEXT);
        assertEquals(1, code);
        assertTrue(capturedErr.toString().contains("Error:"));
    }

    @Test
    void blankPromptStillRuns() {
        var loop = loopReturning("");
        int code = PrintMode.run("", loop, Args.OutputMode.TEXT);
        assertEquals(0, code);
    }
}
