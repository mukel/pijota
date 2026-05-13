package ai.pijota.tools;

import ai.pijota.util.ProcessRunner;
import ai.pijota.util.Util;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class GrepTool implements Tool {
    private static final int DEFAULT_LIMIT = 100;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_LINE_LENGTH = 500;
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final int MAX_CAPTURE_BYTES = 1_000_000;
    private volatile Process currentProcess;

    @Override
    public String name() { return "grep"; }

    @Override
    public String description() {
        return "Search file contents for a pattern. Returns matching lines with file paths and line numbers. "
            + "Respects .gitignore. Output is truncated to 100 matches or 100KB. Long lines are truncated.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string",
                    "description", "Search pattern (regex or literal string)"),
                "path", Map.of("type", "string",
                    "description", "Directory or file to search (default: current directory)"),
                "glob", Map.of("type", "string",
                    "description", "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'"),
                "ignoreCase", Map.of("type", "boolean",
                    "description", "Case-insensitive search (default: false)"),
                "literal", Map.of("type", "boolean",
                    "description", "Treat pattern as literal string instead of regex (default: false)"),
                "context", Map.of("type", "integer",
                    "description", "Number of surrounding lines to show before and after each match (default 0)"),
                "limit", Map.of("type", "integer",
                    "description", "Maximum number of matches to return (default: 100)"),
                "timeout", Map.of("type", "number",
                    "description", "Timeout in seconds before ripgrep is killed (default 30)")
            ),
            "required", List.of("pattern"),
            "additionalProperties", false
        );
    }

    @Override
    public boolean defaultEnabled() { return true; }

    @Override
    public CompletableFuture<ToolResult> execute(
            String toolCallId, Map<String, Object> args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pattern = Util.getString(args, "pattern");
                if (pattern == null || pattern.isBlank()) return ToolResult.error("No pattern provided");

                Path base = Util.resolveDir(sessionCwd);
                Path searchPath = Util.resolveSearchPath(base, Util.getString(args, "path"));
                if (!Files.exists(searchPath)) return ToolResult.error("Path not found: " + searchPath);

                boolean isDirectory = Files.isDirectory(searchPath);
                String glob = Util.coalesce(Util.getString(args, "glob"), Util.getString(args, "include"));
                boolean ignoreCase = Boolean.TRUE.equals(args.get("ignoreCase"));
                boolean literal = Boolean.TRUE.equals(args.get("literal"));
                int context = Math.max(0, Util.getInt(args, "context", 0));
                int limit = Math.max(1, Util.getInt(args, "limit", DEFAULT_LIMIT));
                long timeoutSeconds = Math.max(1, Util.getLong(args, "timeout", DEFAULT_TIMEOUT_SECONDS));

                var command = new ArrayList<String>();
                command.add("rg");
                command.add("--json");
                command.add("--line-number");
                command.add("--color=never");
                command.add("--hidden");
                if (ignoreCase) command.add("--ignore-case");
                if (literal) command.add("--fixed-strings");
                if (glob != null && !glob.isBlank()) {
                    command.add("--glob");
                    command.add(glob);
                }
                command.add("--");
                command.add(pattern);
                command.add(searchPath.toString());

                var result = ProcessRunner.command(command)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .maxOutputBytes(MAX_CAPTURE_BYTES)
                    .captureStderr(true)
                    .onStart(p -> currentProcess = p)
                    .run();

                if (result.timedOut()) return ToolResult.error("ripgrep timed out after "
                    + timeoutSeconds + " seconds");
                if (result.exitCode() != 0 && result.exitCode() != 1) {
                    String err = result.stderr().strip();
                    return ToolResult.error(err.isBlank()
                        ? "ripgrep exited with code " + result.exitCode()
                        : err);
                }

                var matches = new ArrayList<Match>();
                boolean limitReached = false;
                for (String line : result.stdout().split("\\R")) {
                    Match match = parseMatch(line);
                    if (match == null) continue;
                    if (matches.size() >= limit) {
                        limitReached = true;
                        break;
                    }
                    matches.add(match);
                }
                if (matches.isEmpty()) return ToolResult.success("No matches found");

                var output = new ArrayList<String>();
                boolean linesTruncated = false;
                for (var match : matches) {
                    if (context == 0) {
                        var truncated = truncateLine(match.text());
                        linesTruncated |= truncated.truncated();
                        output.add(formatPath(searchPath, match.path(), isDirectory) + ":" + match.line() + ": " + truncated.text());
                    } else {
                        var block = contextBlock(searchPath, match, isDirectory, context);
                        linesTruncated |= block.truncated();
                        output.addAll(block.lines());
                    }
                }

                var sb = new StringBuilder(String.join("\n", output));
                var notices = new ArrayList<String>();
                var details = new LinkedHashMap<String, Object>();
                details.put("matchCount", matches.size());
                if (limitReached) notices.add(limit + " matches limit reached. Use limit=" + (limit * 2)
                    + " for more, or refine pattern");
                if (linesTruncated) notices.add("Some lines truncated to " + MAX_LINE_LENGTH
                    + " chars. Use read to see full lines");
                if (sb.length() > MAX_OUTPUT_CHARS) {
                    String truncated = Util.truncateHeadTail(sb.toString(), MAX_OUTPUT_CHARS);
                    sb = new StringBuilder(truncated);
                    notices.add("Output truncated to " + (MAX_OUTPUT_CHARS / 1024) + "KB");
                    details.put("truncated", true);
                    details.put("maxChars", MAX_OUTPUT_CHARS);
                }
                if (limitReached) details.put("matchLimitReached", limit);
                if (linesTruncated) details.put("linesTruncated", true);
                if (result.outputTruncated()) {
                    notices.add("ripgrep output capture truncated; refine pattern or path");
                    details.put("captureTruncated", true);
                }
                if (!notices.isEmpty()) sb.append("\n\n[").append(String.join(". ", notices)).append(']');
                return ToolResult.success(sb.toString(), details);
            } catch (IOException e) {
                return ToolResult.error("ripgrep error: " + e.getMessage());
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                Process p = currentProcess;
                if (p != null) ProcessRunner.killTree(p);
                return ToolResult.error("ripgrep interrupted");
            } finally {
                currentProcess = null;
            }
        });
    }

    @Override
    public void abort() {
        ProcessRunner.abortIfAlive(currentProcess);
    }

    private static Match parseMatch(String raw) {
        try {
            JsonNode root = Util.MAPPER.readTree(raw);
            if (!"match".equals(root.path("type").asText())) return null;
            JsonNode data = root.path("data");
            String path = data.path("path").path("text").asText(null);
            int line = data.path("line_number").asInt(-1);
            String text = data.path("lines").path("text").asText("")
                .replace("\r\n", "\n").replace("\r", "").replaceAll("\n$", "");
            return path == null || line < 1 ? null : new Match(Path.of(path), line, text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ContextBlock contextBlock(Path searchPath, Match match, boolean isDirectory, int context)
            throws IOException {
        var fileLines = Files.readAllLines(match.path());
        var out = new ArrayList<String>();
        boolean truncated = false;
        int start = Math.max(1, match.line() - context);
        int end = Math.min(fileLines.size(), match.line() + context);
        String displayPath = formatPath(searchPath, match.path(), isDirectory);
        for (int line = start; line <= end; line++) {
            var text = truncateLine(fileLines.get(line - 1).replace("\r", ""));
            truncated |= text.truncated();
            String sep = line == match.line() ? ":" : "-";
            out.add(displayPath + sep + line + sep + " " + text.text());
        }
        return new ContextBlock(out, truncated);
    }

    private static String formatPath(Path searchPath, Path file, boolean isDirectory) {
        if (isDirectory) {
            try {
                Path relative = searchPath.relativize(file);
                if (!relative.toString().startsWith("..")) return relative.toString().replace('\\', '/');
            } catch (IllegalArgumentException ignored) {}
        }
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    private static TruncatedLine truncateLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) return new TruncatedLine(line, false);
        return new TruncatedLine(line.substring(0, MAX_LINE_LENGTH - 1) + "…", true);
    }

    private record Match(Path path, int line, String text) {}
    private record ContextBlock(List<String> lines, boolean truncated) {}
    private record TruncatedLine(String text, boolean truncated) {}
}
