package ai.pijota.tools;

import ai.pijota.util.ProcessRunner;
import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

public final class BashTool implements Tool {

    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_BYTES = 100_000;
    private static final int PREVIEW_LINES = 20;
    private static final int PREVIEW_HEAD_LINES = 10;
    private static final int PREVIEW_TAIL_LINES = 10;
    private final String shellPath;
    private final String shellPrefix;
    private volatile Process currentProcess;

    public BashTool(String shellPath, String shellPrefix) {
        this.shellPath = shellPath;
        this.shellPrefix = shellPrefix;
    }

    @Override public String name() { return "bash"; }

    @Override
    public String description() {
        return "Execute a bash command in the current working directory. Returns stdout and stderr. "
            + "Output is truncated at 100KB. Optionally provide a timeout in seconds (default 120s).";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description", "Shell command to execute"),
                "timeout", Map.of("type", "number",
                    "description", "Timeout in seconds (optional)")
            ),
            "required", List.of("command"),
            "additionalProperties", false
        );
    }

    @Override public ExecutionMode executionMode() { return ExecutionMode.SEQUENTIAL; }

    @Override
    public CompletableFuture<ToolResult> execute(
            String toolCallId, Map<String, Object> args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String command = (String) args.get("command");
                Path workdir = args.containsKey("workdir")
                    ? Path.of((String) args.get("workdir"))
                    : Util.resolveDir(sessionCwd);

                if (!Files.isDirectory(workdir))
                    return ToolResult.error("Working directory does not exist: " + workdir);

                // Timeout in SECONDS (pi-compatible)
                long timeoutSeconds = (long) Util.getLong(args, "timeout",
                    DEFAULT_TIMEOUT_SECONDS);

                String fullCmd = shellPrefix != null
                    ? shellPrefix + "\n" + command : command;

                var result = ProcessRunner.command(List.of(shellPath, "-c", fullCmd))
                    .workDir(workdir)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .maxOutputBytes(MAX_OUTPUT_BYTES)
                    .redirectErrorStream(true)
                    .onStart(p -> currentProcess = p)
                    .run();

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
                        details.put("outputPath", saveOutput(output).toString());
                    } catch (IOException ignored) {}
                }

                if (result.timedOut()) return new ToolResult(output, true, details);
                if (result.exitCode() != 0) return new ToolResult(output, true, details);

                if (output.isEmpty())
                    return ToolResult.success("(no output)", details);
                return ToolResult.success(output, details);
            } catch (IOException e) {
                return ToolResult.error("Bash error: " + e.getMessage());
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                Process p = currentProcess;
                if (p != null) ProcessRunner.killTree(p);
                return ToolResult.error("Command interrupted");
            } finally {
                currentProcess = null;
            }
        });
    }

    @Override
    public ToolResultView renderResult(Map<String, Object> args, ToolResult result, String cwd) {
        String summary;
        Map<String, Object> details = result.details();
        String prefix = "";
        if (Boolean.TRUE.equals(details.get("timedOut"))) prefix = "(timed out) ";
        String output = result.content();
        if (output == null || output.isBlank() || "(no output)".equals(output)) {
            summary = prefix + "(no output)";
            return ToolResultView.of(summary);
        }
        summary = prefix + output.lines().count() + " line(s)";

        var body = new java.util.ArrayList<String>();
        if (output != null && !output.isBlank() && !"(no output)".equals(output)) {
            var lines = output.lines().toList();
            if (lines.size() <= PREVIEW_LINES) {
                for (String line : lines) body.add("│ " + line);
            } else {
                for (int i = 0; i < PREVIEW_HEAD_LINES; i++) {
                    body.add("│ " + lines.get(i));
                }
                int omitted = lines.size() - PREVIEW_HEAD_LINES - PREVIEW_TAIL_LINES;
                body.add("┴");
                body.add("⋯ " + omitted + " omitted line(s)");
                body.add("┬");
                for (int i = lines.size() - PREVIEW_TAIL_LINES; i < lines.size(); i++) {
                    body.add("│ " + lines.get(i));
                }
            }
            if (lines.size() > PREVIEW_LINES && details.get("outputPath") instanceof String path) {
                body.add("╰→ full output: " + path);
            }
        }
        return ToolResultView.of(summary, body);
    }

    private static Path saveOutput(String output) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pijota");
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "bash-", ".log");
        Files.writeString(file, output, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
    }

    @Override
    public void abort() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) ProcessRunner.killTree(p);
    }
}
