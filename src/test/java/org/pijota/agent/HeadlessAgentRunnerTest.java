package org.pijota.agent;

import org.pijota.extensions.ExtensionRegistry;
import org.pijota.messages.ToolCall;
import org.pijota.testkit.FauxLlm;
import org.pijota.testkit.Ndjson;
import org.pijota.testkit.StdioCapture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.pijota.testkit.FauxLlm.Response.contentWithUsage;

class HeadlessAgentRunnerTest {

    @TempDir
    Path tempDir;

    @RegisterExtension
    StdioCapture stdio = new StdioCapture();

    @Test
    void successfulRunReturnsZeroAndEmitsDoneEvent() {
        var llm = new FauxLlm().enqueue(contentWithUsage("hello world"));
        stdio.feedInput("{\"description\":\"say hi\",\"cwd\":\"" + tempDir.toString().replace("\\", "\\\\") + "\"}");

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(0, exitCode);
        var done = Ndjson.event(stdio.out(), "done");
        assertEquals("hello world", done.get("output"));
        assertNull(done.get("error"));
    }

    @Test
    void invalidInputReturnsOneAndPrintsError() {
        var llm = new FauxLlm();
        stdio.feedInput("not valid json");

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(1, exitCode);
        assertTrue(stdio.err().contains("ERROR"));
    }

    @Test
    void toolExecutionEmitsStartResultAndEndEvents() throws IOException {
        var file = tempDir.resolve("data.txt");
        Files.writeString(file, "file contents");
        var llm = new FauxLlm().enqueue(FauxLlm.Response.toolCall(new ToolCall("tc1", "read",
                                        "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}")))
                                        .enqueue(contentWithUsage("done"));
        stdio.feedInput("{\"description\":\"read file\",\"tools\":[\"read\"],\"cwd\":\""
                                        + tempDir.toString().replace("\\", "\\\\") + "\"}");

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(0, exitCode);
        String output = stdio.out();
        assertNotNull(Ndjson.event(output, "tool_start"));
        assertNotNull(Ndjson.event(output, "tool_result"));
        assertNotNull(Ndjson.event(output, "tool_end"));
        assertEquals(1, ((Number) Ndjson.event(output, "done").get("toolCount")).intValue());
    }

    @Test
    void writesAHumanReadableLogFileWhenRequested() throws IOException {
        var file = tempDir.resolve("data.txt");
        Files.writeString(file, "hi");
        var log = tempDir.resolve("logs/run.log"); // a missing parent dir must be created
        var llm = new FauxLlm().enqueue(FauxLlm.Response
                                        .toolCall(new ToolCall("tc1", "read", "{\"path\":\"" + file + "\"}")))
                                        .enqueue(contentWithUsage("done"));
        stdio.feedInputJson(Map.of("description", "read it", "tools", List.of("read"), "cwd", tempDir.toString(),
                                        "logFile", log.toString()));

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(0, exitCode);
        assertNotNull(Ndjson.event(stdio.out(), "log"), "emits a log event naming the file");
        assertTrue(Files.exists(log), "log file (and its parent dir) is created");
        assertTrue(Files.readString(log).contains("-> read"), "tool activity is written to the log");
    }

    @Test
    void streamingDeltasEmitStreamEvents() {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("Hello ", "world\n"));
        stdio.feedInputJson(Map.of("description", "hi", "cwd", tempDir.toString()));

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(0, exitCode);
        assertNotNull(Ndjson.event(stdio.out(), "stream"), "streaming deltas surface as stream events: " + stdio.out());
    }

    @Test
    void taskProviderAndModelOverrideTheClient() {
        var llm = new FauxLlm().enqueue(contentWithUsage("ok"));
        stdio.feedInputJson(Map.of("description", "x", "cwd", tempDir.toString(), "provider", "anthropic", "model",
                                        "claude-x", "apiKey", "sk-1"));

        HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals("anthropic", llm.provider(), "the task's provider overrides the client");
        assertEquals("claude-x", llm.model());
    }

    @Test
    void llmErrorEmitsDoneEventWithError() {
        // A failed turn must surface as a done event carrying a non-blank error; SubAgentLauncher relies on this
        // to report sub-agent failures instead of treating them as empty successes.
        var llm = new FauxLlm().enqueueError(new RuntimeException("401 unauthorized"));
        stdio.feedInput("{\"description\":\"x\",\"cwd\":\"" + tempDir.toString().replace("\\", "\\\\") + "\"}");

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(1, exitCode);
        var done = Ndjson.event(stdio.out(), "done");
        assertNotNull(done.get("error"), "done event must carry the error");
        assertFalse(((String) done.get("error")).isBlank());
    }
}
