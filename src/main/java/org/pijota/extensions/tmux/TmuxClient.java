package org.pijota.extensions.tmux;

import org.pijota.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around the {@code tmux} CLI. All methods are synchronous and short-lived; callers run them on their own
 * threads. The class is stateless except for the command runner, which is injected for tests.
 */
public final class TmuxClient {

    private static final String TMUX = "tmux";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 100_000;

    @FunctionalInterface
    public interface CommandRunner {
        Result run(List<String> command, Duration timeout) throws IOException;
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean success() {
            return exitCode == 0;
        }
    }

    private final CommandRunner runner;

    public TmuxClient() {
        this(defaultRunner());
    }

    TmuxClient(CommandRunner runner) {
        this.runner = runner;
    }

    private static CommandRunner defaultRunner() {
        return (command, timeout) -> {
            var result = ProcessRunner.command(command).timeout(timeout).maxOutputBytes(MAX_OUTPUT_BYTES).run();
            return new Result(result.exitCode(), result.stdout(), result.stderr());
        };
    }

    /** True if tmux is installed and runnable. */
    public boolean hasTmux() {
        try {
            var result = runner.run(List.of(TMUX, "-V"), Duration.ofSeconds(5));
            return result.success();
        } catch (IOException e) {
            return false;
        }
    }

    /** Create a new detached session running the given command in the given working directory. */
    public Result newSession(String name, Path workdir, String command) {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of(TMUX, "new-session", "-d", "-s", name));
        if (workdir != null && Files.isDirectory(workdir)) {
            cmd.add("-c");
            cmd.add(workdir.toAbsolutePath().toString());
        }
        cmd.add(command);
        return run(cmd, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    /** Send literal keys to a target pane. */
    public Result sendKeys(String target, String keys, boolean appendEnter) {
        var cmd = new ArrayList<>(List.of(TMUX, "send-keys", "-t", target));
        cmd.add(keys);
        if (appendEnter) {
            cmd.add("Enter");
        }
        return run(cmd, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    /** Capture the last {@code lines} lines of a pane. */
    public Result capturePane(String target, int lines) {
        int start = -Math.max(1, Math.min(lines, 10_000));
        return run(List.of(TMUX, "capture-pane", "-t", target, "-p", "-S", String.valueOf(start)),
                                        Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    public Result listSessions() {
        return run(List.of(TMUX, "list-sessions", "-F", "#{session_name}\t#{session_id}"),
                                        Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    public Result listPanes(String session) {
        return run(List.of(TMUX, "list-panes", "-t", session, "-F", "#{pane_id}\t#{pane_pid}\t#{pane_title}"),
                                        Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    public boolean sessionExists(String name) {
        var result = run(List.of(TMUX, "has-session", "-t", name), Duration.ofSeconds(5));
        return result.success();
    }

    public Result killSession(String name) {
        return run(List.of(TMUX, "kill-session", "-t", name), Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    public Result killPane(String target) {
        return run(List.of(TMUX, "kill-pane", "-t", target), Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    private Result run(List<String> command, Duration timeout) {
        try {
            return runner.run(command, timeout);
        } catch (IOException e) {
            String msg = ProcessRunner.isCommandNotFound(e)
                                            ? "tmux not found — install tmux to use this extension"
                                            : e.getMessage();
            return new Result(-1, "", msg);
        }
    }
}
