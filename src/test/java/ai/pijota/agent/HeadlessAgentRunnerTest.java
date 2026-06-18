package ai.pijota.agent;

import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.messages.ToolCall;
import ai.pijota.testkit.FauxLlm;
import ai.pijota.testkit.StdioCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static ai.pijota.testkit.FauxLlm.Response.contentWithUsage;
import static org.junit.jupiter.api.Assertions.*;

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
        String output = stdio.out();
        assertTrue(output.contains("\"type\":\"done\""));
        assertTrue(output.contains("\"output\":\"hello world\""));
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
        assertTrue(output.contains("\"type\":\"tool_start\""));
        assertTrue(output.contains("\"type\":\"tool_result\""));
        assertTrue(output.contains("\"type\":\"tool_end\""));
        assertTrue(output.contains("\"toolCount\":1"));
    }
}
