package org.pijota.tools;

import java.util.ArrayList;

import org.pijota.llm.ToolSchema;
import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.time.Duration;

public final class BashTool implements Tool {
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

    @FunctionalInterface
    interface CommandExecutor {
        ProcessRunner.Result execute(List<String> command, Path workDir, Duration timeout, int maxOutputBytes,
                                        boolean redirectErrorStream, Map<String, String> environment,
                                        Consumer<Process> onStart, Consumer<String> onOutput) throws IOException;
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
        return (cmd, dir, timeout, maxBytes, redirectError, env, onStart, onOutput) -> {
            var builder = ProcessRunner.command(cmd).workDir(dir).timeout(timeout).maxOutputBytes(maxBytes)
                                            .redirectErrorStream(redirectError).environment(env);
            if (onStart != null)
                builder.onStart(onStart);
            if (onOutput != null)
                builder.onStdoutChunk(onOutput);
            return builder.run();
        };
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String glyph() {
        return "$";
    }

    @Override
    public boolean hasResultView() {
        return true;
    }

    @Override
    public String description() {
        return "Execute a bash command in the current working directory (for builds, tests, git, and other shell "
                                        + "commands). Returns stdout and stderr; output is truncated at 100KB; optional timeout in seconds "
                                        + "(default 120s). Do NOT use it to read or search files — use read/grep/glob/ls instead, which are "
                                        + "faster and respect .gitignore.";
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                String command = args.getString("command");
                Path workdir = args.has("workdir") ? Path.of(args.getString("workdir")) : Util.resolveDir(sessionCwd);

                if (!Files.isDirectory(workdir))
                    return ToolResult.error("Working directory does not exist: " + workdir);

                // Timeout in SECONDS (pi-compatible)
                long timeoutSeconds = args.getLong("timeout", DEFAULT_TIMEOUT_SECONDS);

                String fullCmd = shellPrefix != null ? shellPrefix + "\n" + command : command;
                Map<String, String> environment = environmentSupplier.get();

                var result = commandExecutor.execute(List.of(shellPath, Platform.shellCommandFlag(shellPath), fullCmd),
                                                workdir, Duration.ofSeconds(timeoutSeconds), MAX_OUTPUT_BYTES, true,
                                                environment, p -> currentProcess = p, onProgress);

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
            } catch (IOException e) {
                if (ProcessRunner.isCommandNotFound(e))
                    return ToolResult.error("Bash error: the configured shell could not be started — check shell_path / $SHELL ("
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
            }
        });
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        String summary;
        Map<String, Object> details = result.details();
        String prefix = "";
        if (Boolean.TRUE.equals(details.get("timedOut")))
            prefix = "(timed out) ";
        String output = result.content();
        if (output == null || output.isBlank() || "(no output)".equals(output)) {
            summary = prefix + "(no output)";
            return ToolResultView.of(summary);
        }
        summary = prefix + output.lines().count() + " line(s)";

        // Bare output lines — the framed layout (┌ … └▸) delimits the block, so no per-line rail. On overflow, show a
        // head+tail preview split by one muted line that carries the omitted count and the saved-output path.
        var body = new ArrayList<String>();
        var lines = output.lines().toList();
        if (lines.size() <= PREVIEW_LINES) {
            body.addAll(lines);
        } else {
            body.addAll(lines.subList(0, PREVIEW_HEAD_LINES));
            int omitted = lines.size() - PREVIEW_HEAD_LINES - PREVIEW_TAIL_LINES;
            String omit = "⋯ " + omitted + " lines omitted";
            if (details.get("outputPath") instanceof String path)
                omit += " · full output: " + path;
            body.add(omit);
            body.addAll(lines.subList(lines.size() - PREVIEW_TAIL_LINES, lines.size()));
        }
        return ToolResultView.of(summary, body);
    }

    @Override
    public void abort() {
        Process p = currentProcess;
        if (p != null && p.isAlive())
            ProcessRunner.killTree(p);
    }
}
