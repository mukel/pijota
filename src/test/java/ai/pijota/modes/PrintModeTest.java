package ai.pijota.modes;

import static org.junit.jupiter.api.Assertions.*;

import ai.pijota.agent.AgentLoop;
import ai.pijota.cli.Args;
import ai.pijota.testkit.FauxLlm;
import ai.pijota.tools.ToolRegistry;

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

    @Test
    void jsonModePrintsMessagesArray() {
        var loop = loopReturning("hello world");
        int code = PrintMode.run("hi", loop, Args.OutputMode.JSON);
        assertEquals(0, code);
        String out = capturedOut.toString();
        assertTrue(out.contains("\"model\": \"unknown\""));
        assertTrue(out.contains("\"role\": \"assistant\""));
        assertTrue(out.contains("hello world"));
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
