package ai.pijota.tools;

import ai.pijota.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    private static BashTool.CommandExecutor fakeExecutor(String stdout, int exitCode, boolean timedOut,
                                    boolean truncated) {
        return (cmd, dir, timeout, maxBytes, redirectError, onStart) -> {
            if (onStart != null)
                onStart.accept(null);
            return new ProcessRunner.Result(exitCode, stdout, "", timedOut, truncated);
        };
    }

    @Test
    void nameIsBash() {
        var tool = new BashTool("/bin/bash", null);
        assertEquals("bash", tool.name());
    }

    @Test
    void executeSimpleCommandSucceeds() {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("hello", 0, false, false));
        var result = run(tool, Map.of("command", "echo hello"), "/tmp");
        assertFalse(result.isError());
        assertTrue(result.content().contains("hello"));
    }

    @Test
    void executeWithShellPrefix() {
        var tool = new BashTool("/bin/zsh", "set -e", fakeExecutor("output", 0, false, false));
        var result = run(tool, Map.of("command", "ls"), "/tmp");
        assertFalse(result.isError());
        assertEquals("output", result.content());
    }

    @Test
    void executeNonZeroExitCodeReturnsError() {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("error msg", 1, false, false));
        var result = run(tool, Map.of("command", "bad"), "/tmp");
        assertTrue(result.isError());
        assertTrue(result.content().contains("error msg"));
        assertTrue(result.content().contains("exited with code 1"));
    }

    @Test
    void executeTimedOutReturnsError() {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("partial", -1, true, false));
        var result = run(tool, Map.of("command", "slow"), "/tmp");
        assertTrue(result.isError());
        assertTrue(result.content().contains("timed out"));
        assertEquals(true, result.details().get("timedOut"));
    }

    @Test
    void executeEmptyOutputReturnsNoOutput() {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("", 0, false, false));
        var result = run(tool, Map.of("command", "echo -n"), "/tmp");
        assertFalse(result.isError());
        assertEquals("(no output)", result.content());
    }

    @Test
    void executeTruncatedOutput() {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("x".repeat(200), 0, false, true));
        var result = run(tool, Map.of("command", "cat large"), "/tmp");
        assertFalse(result.isError());
        assertTrue(result.content().contains("truncated"));
        assertEquals(true, result.details().get("truncated"));
    }

    @Test
    void executeWithWorkdir(@TempDir Path tmp) {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("ok", 0, false, false));
        var result = run(tool, Map.of("command", "pwd", "workdir", tmp.toString()), "/tmp");
        assertFalse(result.isError());
    }

    @Test
    void executeNonexistentWorkdirReturnsError() {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("", 0, false, false));
        var result = run(tool, Map.of("command", "pwd"), "/nonexistent/dir/xyz");
        assertTrue(result.isError() || result.content().contains("does not exist"));
    }

    @Test
    void executeWithDetailsIncludesExitCode() {
        var tool = new BashTool("/bin/bash", null, fakeExecutor("out", 2, false, false));
        var result = run(tool, Map.of("command", "cmd"), "/tmp");
        assertEquals(2, result.details().get("exitCode"));
    }

    @Test
    void executionModeIsSequential() {
        var tool = new BashTool("/bin/bash", null);
        assertEquals(Tool.ExecutionMode.SEQUENTIAL, tool.executionMode());
    }

    @Test
    void renderResultShortOutput() {
        var tool = new BashTool("/bin/bash", null);
        var tr = Tool.ToolResult.success("line1\nline2\nline3");
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");
        assertTrue(view.summary().contains("line(s)"));
        assertEquals(3, view.body().size());
    }

    @Test
    void renderResultLongOutputTruncatesInPreview() {
        var tool = new BashTool("/bin/bash", null);
        var lines = new StringBuilder();
        for (int i = 0; i < 50; i++)
            lines.append("line ").append(i).append('\n');
        var tr = Tool.ToolResult.success(lines.toString());
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");
        assertTrue(view.body().stream().anyMatch(l -> l.contains("omitted")));
    }

    @Test
    void renderResultTimedOutShowsPrefix() {
        var tool = new BashTool("/bin/bash", null);
        var tr = new Tool.ToolResult("timed out output", true, Map.of("timedOut", true));
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");
        assertTrue(view.summary().contains("timed out"));
    }

    @Test
    void renderResultEmptyOutput() {
        var tool = new BashTool("/bin/bash", null);
        var tr = Tool.ToolResult.success("");
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");
        assertTrue(view.summary().contains("no output"));
    }

    @Test
    void renderResultNoOutputExplicit() {
        var tool = new BashTool("/bin/bash", null);
        var tr = Tool.ToolResult.success("(no output)");
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");
        assertTrue(view.summary().contains("no output"));
    }

    @Test
    void renderResultWithOutputPathInBody() {
        var tool = new BashTool("/bin/bash", null);
        var lines = new StringBuilder();
        for (int i = 0; i < 50; i++)
            lines.append("line ").append(i).append('\n');
        var tr = new Tool.ToolResult(lines.toString(), false, Map.of("outputPath", "/tmp/bash-xyz.log"));
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");
        assertTrue(view.body().stream().anyMatch(l -> l.contains("full output")));
    }

    @Test
    void abortWhenNoProcessDoesNotThrow() {
        var tool = new BashTool("/bin/bash", null);
        assertDoesNotThrow(() -> tool.abort());
    }

    @Test
    void descriptionIsNotEmpty() {
        var tool = new BashTool("/bin/bash", null);
        assertNotNull(tool.description());
        assertFalse(tool.description().isBlank());
    }

    @Test
    void schemaHasCommandRequired() {
        var tool = new BashTool("/bin/bash", null);
        var schema = tool.schema();
        assertTrue(schema.get("required") instanceof List);
    }

    @Test
    void ioExceptionInExecutorReturnsError() {
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, onStart) -> {
            throw new IOException("disk full");
        };
        var tool = new BashTool("/bin/bash", null, executor);
        var result = run(tool, Map.of("command", "ls"), "/tmp");
        assertTrue(result.isError());
    }

    @Test
    void missingConfiguredShellReturnsActionableError() {
        // the JVM "binary not on PATH" failure, as a missing shell_path / $SHELL would raise
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, onStart) -> {
            throw new IOException("Cannot run program \"/bin/no-such-shell\": error=2, No such file or directory");
        };
        var tool = new BashTool("/bin/no-such-shell", null, executor);
        var result = run(tool, Map.of("command", "ls"), "/tmp");
        assertTrue(result.isError());
        assertTrue(result.content().contains("shell"), result.content());
    }
}
