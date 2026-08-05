package org.pijota.tools;

import org.pijota.util.ProcessRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.pijota.testkit.ToolRunner.run;

class BashToolTest {

    private static BashTool.CommandExecutor fakeExecutor(String stdout, int exitCode, boolean timedOut,
                                    boolean truncated) {
        return (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput, detachSignal) -> {
            if (onStart != null)
                onStart.accept(null);
            return new ProcessRunner.Result(exitCode, stdout, "", timedOut, truncated);
        };
    }

    /**
     * The card header already says {@code exit 2}; repeating it in the body stated the same fact twice in two formats
     * with a blank line between them, and counted as output -- a command printing one line was summarised as three. The
     * model still receives the marker in the result content; only the transcript drops it.
     */
    @Test
    void theRenderedCardDoesNotRepeatTheExitCodeTheHeaderAlreadyShows() {
        var result = new Tool.ToolResult("boom\n[Command exited with code 2]", true, Map.of("exitCode", 2));

        var view = new BashTool("/bin/sh", null, Map::of).renderResult(new Tool.Args(Map.of("command", "false")),
                                        result, ".");

        assertFalse(String.join("\n", view.body()).contains("Command exited with code"), view.body().toString());
        assertEquals("1 line", view.summary(), "the marker was counted as output");
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

    /**
     * The two shell settings, asserted on the argv rather than on the output. The test above constructs a tool with
     * both and only checks what came back, so it passes just as well when neither reaches the command; these are the
     * settings that decide which interpreter runs and what runs before the model's line.
     */
    @Test
    void shellPathAndCommandPrefixReachTheCommand() {
        var seen = new AtomicReference<List<String>>();
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput,
                                        detachSignal) -> {
            seen.set(cmd);
            return new ProcessRunner.Result(0, "ok", "", false, false);
        };

        run(new BashTool("/usr/bin/zsh", "source .env", executor), Map.of("command", "echo hi"), "/tmp");

        assertEquals("/usr/bin/zsh", seen.get().getFirst(), "shell_path picks the interpreter");
        assertEquals("source .env\necho hi", seen.get().getLast(),
                                        "shell_command_prefix goes on its own line, so a trailing comment cannot "
                                                                        + "swallow the command");
    }

    @Test
    void noShellPrefixLeavesTheCommandAlone() {
        var seen = new AtomicReference<List<String>>();
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput,
                                        detachSignal) -> {
            seen.set(cmd);
            return new ProcessRunner.Result(0, "ok", "", false, false);
        };

        run(new BashTool("/bin/bash", null, executor), Map.of("command", "echo hi"), "/tmp");

        assertEquals("echo hi", seen.get().getLast());
    }

    @Test
    void passesPijotaEnvironmentToExecutor() {
        var seen = new AtomicReference<Map<String, String>>();
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput,
                                        detachSignal) -> {
            seen.set(env);
            return new ProcessRunner.Result(0, "ok", "", false, false);
        };
        var tool = new BashTool("/bin/bash", null, executor,
                                        () -> Map.of("PIJOTA_PROVIDER", "anthropic", "PIJOTA_MODEL", "claude"));
        var result = run(tool, Map.of("command", "env"), "/tmp");

        assertFalse(result.isError());
        assertEquals("anthropic", seen.get().get("PIJOTA_PROVIDER"));
        assertEquals("claude", seen.get().get("PIJOTA_MODEL"));
    }

    @Test
    void executeWithProgressCallbackForwardsOutputChunks() {
        var seen = new ArrayList<String>();
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput,
                                        detachSignal) -> {
            onOutput.accept("partial ");
            onOutput.accept("output");
            return new ProcessRunner.Result(0, "partial output", "", false, false);
        };
        var tool = new BashTool("/bin/bash", null, executor);

        var result = tool.execute("call-1", new Tool.Args(Map.of("command", "echo hi")), "/tmp", seen::add).join();

        assertFalse(result.isError());
        assertEquals(List.of("partial ", "output"), seen);
        assertEquals("partial output", result.content());
    }

    @Test
    void executeWithNullProgressCallbackStillProvidesTheDetachSink() {
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput,
                                        detachSignal) -> {
            // Even without a progress callback the executor gets a sink: detach() needs the output-so-far tail.
            assertNotNull(onOutput);
            onOutput.accept("ignored quietly");
            return new ProcessRunner.Result(0, "ok", "", false, false);
        };
        var tool = new BashTool("/bin/bash", null, executor);

        var result = run(tool, Map.of("command", "echo ok"), "/tmp");

        assertFalse(result.isError());
        assertEquals("ok", result.content());
    }

    @Test
    void defaultExecutorReportsRealProcessOutputChunks() {
        var chunks = new ArrayList<String>();
        var tool = new BashTool("/bin/sh", null);

        var result = tool.execute("call-1", new Tool.Args(Map.of("command", "printf real-output")), "/tmp", chunks::add)
                                        .join();

        assertFalse(result.isError());
        assertEquals("real-output", result.content());
        assertEquals("real-output", String.join("", chunks));
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
    void detachWithNothingRunningIsEmpty() {
        assertTrue(new BashTool("/bin/bash", null).detach().isEmpty());
    }

    @Test
    void detachHandsOffTheProcessAndTheAdopterCompletesTheCall() throws Exception {
        Process real = new ProcessBuilder("sleep", "30").start();
        try {
            var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart,
                                            onOutput, detachSignal) -> {
                onStart.accept(real);
                onOutput.accept("early output\n");
                try {
                    detachSignal.get(5, TimeUnit.SECONDS); // a long-running command: block until
                                                           // the user detaches
                } catch (Exception e) {
                    throw new IOException("expected a detach", e);
                }
                onOutput.accept("late output\n"); // the pump keeps feeding after the hand-off
                return new ProcessRunner.Result(-1, "early output\n", "", false, false, true);
            };
            var tool = new BashTool("/bin/bash", null, executor);
            var future = tool.execute("id", new Tool.Args(Map.of("command", "sleep 30")), "/tmp", chunk -> {});

            // The running call becomes detachable as soon as the executor has published the process.
            var detached = Optional.<Tool.DetachedProcess>empty();
            long deadline = System.currentTimeMillis() + 5000;
            while (detached.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
                detached = tool.detach();
            }
            var handoff = detached.orElseThrow();

            var adopted = new StringBuilder();
            String soFar = handoff.redirectOutput().apply(s -> {
                synchronized (adopted) {
                    adopted.append(s);
                }
            });
            assertTrue(soFar.contains("early output"), "output so far travels with the hand-off");
            handoff.complete().accept(Tool.ToolResult.success("moved to background",
                                            Map.of("jobId", "bg_9", "backgrounded", true)));

            var result = future.get(2, TimeUnit.SECONDS);
            assertEquals("moved to background", result.content(), "the synthetic result resolves the call");

            // The executor's own (detached) return must not overwrite the synthetic result…
            Thread.sleep(150);
            assertEquals("moved to background", future.join().content());
            synchronized (adopted) {
                assertTrue(adopted.toString().contains("late output"),
                                                "post-detach output reaches the adopter: " + adopted);
            }
            // …and an interrupt after the hand-off must not kill the adopted process.
            tool.abort();
            assertTrue(real.isAlive(), "abort() after detach must not touch the adopted process");
        } finally {
            real.destroyForcibly();
        }
    }

    @Test
    void renderResultShortOutput() {
        var tool = new BashTool("/bin/bash", null);
        var tr = Tool.ToolResult.success("line1\nline2\nline3");
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");
        assertTrue(view.summary().contains("3 lines"));
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
    void renderResultPutsTheOutputPathOnItsOwnLineHomeAbbreviated() {
        var tool = new BashTool("/bin/bash", null);
        var lines = new StringBuilder();
        for (int i = 0; i < 50; i++)
            lines.append("line ").append(i).append('\n');
        String path = System.getProperty("user.home") + "/.pijota/tool-output/bash-123.txt";
        var tr = new Tool.ToolResult(lines.toString(), false, Map.of("outputPath", path));
        var view = tool.renderResult(new Tool.Args(Map.of()), tr, "/cwd");

        String pathLine = view.body().stream().filter(l -> l.contains("full output")).findFirst().orElseThrow();
        assertTrue(pathLine.contains("~/.pijota/tool-output/bash-123.txt"), "home is abbreviated: " + pathLine);
        // The omitted-lines cue is a SEPARATE, short line so a narrow terminal never has to wrap the path onto it.
        String cue = view.body().stream().filter(l -> l.contains("omitted")).findFirst().orElseThrow();
        assertFalse(cue.contains("full output"), "the ⋯ cue and the path are on different lines: " + cue);
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
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput,
                                        detachSignal) -> {
            throw new IOException("disk full");
        };
        var tool = new BashTool("/bin/bash", null, executor);
        var result = run(tool, Map.of("command", "ls"), "/tmp");
        assertTrue(result.isError());
    }

    @Test
    void missingConfiguredShellReturnsActionableError() {
        // the JVM "binary not on PATH" failure, as a missing shell_path / $SHELL would raise
        var executor = (BashTool.CommandExecutor) (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput,
                                        detachSignal) -> {
            throw new IOException("Cannot run program \"/bin/no-such-shell\": error=2, No such file or directory");
        };
        var tool = new BashTool("/bin/no-such-shell", null, executor);
        var result = run(tool, Map.of("command", "ls"), "/tmp");
        assertTrue(result.isError());
        assertTrue(result.content().contains("shell"), result.content());
    }
}
