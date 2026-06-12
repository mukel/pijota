package ai.pijota.agent;

import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.messages.ToolCall;
import ai.pijota.testkit.FauxLlm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HeadlessAgentRunnerTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;
    private java.io.InputStream originalIn;

    @BeforeEach
    void setUpStreams() {
        originalOut = System.out;
        originalErr = System.err;
        originalIn = System.in;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
    }

    private void setInput(String json) {
        System.setIn(new ByteArrayInputStream(json.getBytes()));
    }

    private static FauxLlm.Response withUsage(String content) {
        return FauxLlm.Response.content(new ai.pijota.messages.Usage(1, 1), content);
    }

    @Test
    void successfulRunReturnsZeroAndEmitsDoneEvent() {
        var llm = new FauxLlm().enqueue(withUsage("hello world"));
        setInput("{\"description\":\"say hi\",\"cwd\":\"" + tempDir.toString().replace("\\", "\\\\") + "\"}");

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("\"type\":\"done\""));
        assertTrue(output.contains("\"output\":\"hello world\""));
    }

    @Test
    void invalidInputReturnsOneAndPrintsError() {
        var llm = new FauxLlm();
        setInput("not valid json");

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("ERROR"));
    }

    @Test
    void toolExecutionEmitsStartResultAndEndEvents() throws IOException {
        var file = tempDir.resolve("data.txt");
        Files.writeString(file, "file contents");
        var llm = new FauxLlm().enqueue(FauxLlm.Response.toolCall(new ToolCall("tc1", "read",
                                        "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}")))
                                        .enqueue(withUsage("done"));
        setInput("{\"description\":\"read file\",\"tools\":[\"read\"],\"cwd\":\""
                                        + tempDir.toString().replace("\\", "\\\\") + "\"}");

        int exitCode = HeadlessAgentRunner.run(llm, new ExtensionRegistry());

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("\"type\":\"tool_start\""));
        assertTrue(output.contains("\"type\":\"tool_result\""));
        assertTrue(output.contains("\"type\":\"tool_end\""));
        assertTrue(output.contains("\"toolCount\":1"));
    }
}
