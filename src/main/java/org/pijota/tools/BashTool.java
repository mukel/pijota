package org.pijota.tools;

import org.pijota.llm.ToolSchema;
import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs a shell command in the working directory: builds, tests, git, anything with no dedicated tool. */
public final class BashTool implements Tool, Tool.Detachable {
    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_BYTES = 100_000;
    private static final int PREVIEW_LINES = 20;
    private static final int PREVIEW_HEAD_LINES = 10;
    private static final int PREVIEW_TAIL_LINES = 10;
    private final String shellPath;
    private final String shellPrefix;
    private final CommandExecutor commandExecutor;
    private final Supplier<Map<String, String>> environmentSupplier;
    private volatile Process currentProcess;
    // Live state of the current call, published for detach(): bash is SEQUENTIAL, so there is at most one.
    private volatile CompletableFuture<ToolResult> inFlight;
    private volatile CompletableFuture<Void> detachSignal;
    private volatile SwitchableSink sink;
    private volatile String currentCommand;

    @FunctionalInterface
    interface CommandExecutor {
        ProcessRunner.Result execute(List<String> command, Path workDir, Duration timeout, int maxOutputBytes,
                                        boolean redirectErrorStream, Map<String, String> environment,
                                        Consumer<Process> onStart, Consumer<String> onOutput,
                                        CompletableFuture<Void> detachSignal) throws IOException;
    }

    /**
     * The chunk consumer handed to the runner: forwards to a re-pointable delegate (progress stream while foreground,
     * the adopted job's buffer after a detach) and keeps a bounded tail so detach can report the output so far.
     */
    private static final class SwitchableSink implements Consumer<String> {
        private static final int TAIL_CHARS = MAX_OUTPUT_BYTES;
        private volatile Consumer<String> delegate;
        private final StringBuilder tail = new StringBuilder(); // guarded by this

        SwitchableSink(Consumer<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(String chunk) {
            Consumer<String> d;
            synchronized (this) {
                tail.append(chunk);
                if (tail.length() > TAIL_CHARS)
                    tail.delete(0, tail.length() - TAIL_CHARS);
                d = delegate; // captured under the same lock as the tail write, so switchAndDrain sees a
                              // consistent world: every chunk is either in the returned tail or delivered to the
                              // new delegate, never lost, never both
            }
            if (d != null) {
                try {
                    d.accept(chunk);
                } catch (Exception ignored) {}
            }
        }

        /** Atomically re-points the feed and returns everything captured before the switch. */
        synchronized String switchAndDrain(Consumer<String> next) {
            this.delegate = next;
            return tail.toString();
        }
    }

    public BashTool(String shellPath, String shellPrefix) {
        this(shellPath, shellPrefix, (Supplier<Map<String, String>>) Map::of);
    }

    public BashTool(String shellPath, String shellPrefix, Supplier<Map<String, String>> environmentSupplier) {
        this(shellPath, shellPrefix, defaultExecutor(), environmentSupplier);
    }

    BashTool(String shellPath, String shellPrefix, CommandExecutor commandExecutor) {
        this(shellPath, shellPrefix, commandExecutor, Map::of);
    }

    BashTool(String shellPath, String shellPrefix, CommandExecutor commandExecutor,
                                    Supplier<Map<String, String>> environmentSupplier) {
        this.shellPath = shellPath;
        this.shellPrefix = shellPrefix;
        this.commandExecutor = commandExecutor;
        this.environmentSupplier = environmentSupplier != null ? environmentSupplier : Map::of;
    }

    private static CommandExecutor defaultExecutor() {
        return (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput, detachSignal) -> {
            var builder = ProcessRunner.command(cmd).workDir(dir).timeout(timeout).maxOutputBytes(maxBytes)
                                            .redirectErrorStream(redirectError).environment(env);
            if (onStart != null)
                builder.onStart(onStart);
            if (onOutput != null)
                builder.onStdoutChunk(onOutput);
            if (detachSignal != null)
                builder.detachSignal(detachSignal);
            return builder.run();
        };
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String glyph() {
        return ToolGlyphs.RUN;
    }

    @Override
    public boolean hasResultView() {
        return true;
    }

    @Override
    public String description() {
        return "Execute a bash command in the current working directory (for builds, tests, git, and other shell "
                                        + "commands). Returns stdout and stderr; output is truncated at 100KB; optional timeout in seconds "
                                        + "(default 120s). Do NOT use it to read or search files; use read/grep/glob/ls instead, which are "
                                        + "faster and respect .gitignore. For long-running processes (servers, watchers) prefer jobs_run. "
                                        + "The user may move a running command to the background, then you receive a job id: poll it with "
                                        + "jobs_output instead of re-running the command.";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("command", "Shell command to execute", true)
                                        .number("timeout", "Timeout in seconds (optional)").build();
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SEQUENTIAL;
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String sessionCwd) {
        return execute(toolCallId, args, sessionCwd, null);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String sessionCwd,
                                    Consumer<String> onProgress) {
        // The future is completed by whichever comes first: the command finishing here, or a user detach completing
        // it with a synthetic "moved to background" result. CompletableFuture.complete makes the race safe (first
        // wins, the loser's complete() is a no-op).
        var future = new CompletableFuture<ToolResult>();
        var signal = new CompletableFuture<Void>();
        var outputSink = new SwitchableSink(onProgress);
        this.inFlight = future;
        this.detachSignal = signal;
        this.sink = outputSink;
        CompletableFuture.runAsync(() -> {
            ToolResult computed = runCommand(args, sessionCwd, outputSink, signal);
            if (computed != null)
                future.complete(computed);
        });
        return future;
    }

    private ToolResult runCommand(Args args, String sessionCwd, SwitchableSink outputSink,
                                    CompletableFuture<Void> signal) {
        try {
            {
                String command = args.getString("command");
                this.currentCommand = command;
                Path workdir = args.has("workdir") ? Path.of(args.getString("workdir")) : Util.resolveDir(sessionCwd);

                if (!Files.isDirectory(workdir))
                    return ToolResult.error("Working directory does not exist: " + workdir);

                // Timeout in SECONDS (pi-compatible)
                long timeoutSeconds = args.getLong("timeout", DEFAULT_TIMEOUT_SECONDS);

                String fullCmd = shellPrefix != null ? shellPrefix + "\n" + command : command;
                Map<String, String> environment = environmentSupplier.get();

                var result = commandExecutor.execute(List.of(shellPath, Platform.shellCommandFlag(shellPath), fullCmd),
                                                workdir, Duration.ofSeconds(timeoutSeconds), MAX_OUTPUT_BYTES, true,
                                                environment, p -> currentProcess = p, outputSink, signal);

                if (result.detached())
                    return null; // detach() already completed the call with its synthetic result

                String output = result.stdout();
                var details = new LinkedHashMap<String, Object>();
                details.put("exitCode", result.exitCode());
                details.put("timedOut", result.timedOut());
                if (result.timedOut()) {
                    output += "\n[Command timed out after " + timeoutSeconds + " seconds]";
                }

                if (result.outputTruncated()) {
                    output += "\n[Output truncated at " + (MAX_OUTPUT_BYTES / 1024) + "KB]";
                    details.put("truncated", true);
                    details.put("maxChars", MAX_OUTPUT_BYTES);
                }

                if (!result.timedOut() && result.exitCode() != 0) {
                    output += "\n[Command exited with code " + result.exitCode() + "]";
                }

                if (!output.isBlank()) {
                    try {
                        details.put("outputPath", ToolOutputStore.save(output, "bash-").toString());
                    } catch (IOException e) {
                        log.debug("Could not persist bash output to a file: {}", e.getMessage());
                    }
                }

                if (result.timedOut() || result.exitCode() != 0)
                    return new ToolResult(output, true, details);

                if (output.isEmpty())
                    return ToolResult.success("(no output)", details);
                return ToolResult.success(output, details);
            }
        } catch (IOException e) {
            if (ProcessRunner.isCommandNotFound(e))
                return ToolResult.error("Bash error: the configured shell could not be started. Check shell_path / $SHELL ("
                                                + e.getMessage() + ")");
            return ToolResult.error("Bash error: " + e.getMessage());
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            Process p = currentProcess;
            if (p != null)
                ProcessRunner.killTree(p);
            return ToolResult.error("Command interrupted");
        } finally {
            currentProcess = null;
            currentCommand = null;
        }
    }

    /**
     * Hands the running command off to the background. Completing the pending call is left to the adopter via
     * {@link Tool.DetachedProcess#complete()} once it knows the job id. Clears {@link #currentProcess} first so a later
     * {@link #abort()} (interrupt) can no longer kill the adopted process. Empty when nothing is running.
     */
    @Override
    public Optional<Tool.DetachedProcess> detach() {
        Process process = currentProcess;
        CompletableFuture<ToolResult> future = inFlight;
        CompletableFuture<Void> signal = detachSignal;
        SwitchableSink outputSink = sink;
        String command = currentCommand;
        if (process == null || future == null || signal == null || outputSink == null || command == null
                                        || !process.isAlive() || future.isDone())
            return Optional.empty();
        currentProcess = null;
        // The signal fires only when the adopter completes the call; by then the output feed is already re-pointed,
        // so ProcessRunner's detached return can never race a chunk past the hand-off.
        return Optional.of(new Tool.DetachedProcess(process, command, outputSink::switchAndDrain, result -> {
            future.complete(result);
            signal.complete(null); // wakes ProcessRunner.run(), which returns detached without killing anything
        }));
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        String summary;
        Map<String, Object> details = result.details();
        // A user-backgrounded call renders as a quiet one-liner; the long model-facing guidance stays out of the
        // transcript; the job id is the only thing the user needs.
        if (details.get("jobId") instanceof String jobId && Boolean.TRUE.equals(details.get("backgrounded")))
            return ToolResultView.of("backgrounded → " + jobId);
        String prefix = "";
        if (Boolean.TRUE.equals(details.get("timedOut")))
            prefix = "(timed out) ";
        String output = stripExitMarker(result.content());
        if (output == null || output.isBlank() || "(no output)".equals(output)) {
            summary = prefix + "(no output)";
            return ToolResultView.of(summary);
        }
        summary = prefix + Util.count(output.lines().count(), "line", "lines");

        // Bare output lines. The framed layout (┌ … └▸) delimits the block, so no per-line rail. On overflow, show a
        // head+tail preview split by a short "⋯ N omitted" cue; the saved-output path follows on its own line
        // (home-abbreviated) so a narrow terminal isn't forced to wrap the cue.
        var body = new ArrayList<String>();
        var lines = output.lines().toList();
        if (lines.size() <= PREVIEW_LINES) {
            body.addAll(lines);
        } else {
            body.addAll(lines.subList(0, PREVIEW_HEAD_LINES));
            int omitted = lines.size() - PREVIEW_HEAD_LINES - PREVIEW_TAIL_LINES;
            body.add("⋯ " + omitted + " lines omitted");
            if (details.get("outputPath") instanceof String path)
                body.add("╰→ full output: " + Util.shortenCwd(path));
            body.addAll(lines.subList(lines.size() - PREVIEW_TAIL_LINES, lines.size()));
        }
        return ToolResultView.of(summary, body);
    }

    /**
     * Drops the trailing {@code [Command exited with code N]} the model is given.
     *
     * <p>
     * The card's header already says {@code exit 2}, so the transcript stated it twice in two formats, with a blank
     * line between them. It also counted as output: a command that printed one line was summarised as three.
     */
    private static String stripExitMarker(String output) {
        if (output == null)
            return null;
        int marker = output.lastIndexOf("\n[Command exited with code ");
        return marker < 0 ? output : output.substring(0, marker).stripTrailing();
    }

    @Override
    public void abort() {
        Process p = currentProcess;
        if (p != null && p.isAlive())
            ProcessRunner.killTree(p);
    }
}
