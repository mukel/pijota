package org.pijota.tools;

import org.pijota.llm.ToolSchema;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Searches file contents for a pattern, answering with the matching lines and where they are. */
public final class GrepTool implements Tool {
    /** Shared "ripgrep is missing" guidance, also surfaced by the {@code rg} extension tool. */
    public static final String RG_NOT_FOUND = "ripgrep (rg) not found on PATH. Install it: https://github.com/BurntSushi/ripgrep";
    private static final int DEFAULT_LIMIT = 100;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_LINE_LENGTH = 500;
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final int MAX_CAPTURE_BYTES = 1_000_000;
    private volatile Process currentProcess;
    private final SearchBackend searchBackend;

    @FunctionalInterface
    interface SearchBackend {
        SearchResult search(String pattern, Path searchPath, boolean ignoreCase, boolean literal, String glob,
                                        int limit, long timeoutSeconds) throws IOException;
    }

    record SearchResult(String stdout, String stderr, int exitCode, boolean timedOut) {
        static SearchResult of(String stdout) {
            return new SearchResult(stdout, "", 0, false);
        }

        static SearchResult error(int code, String stderr) {
            return new SearchResult("", stderr, code, false);
        }
    }

    public GrepTool() {
        this.searchBackend = this::processSearch;
    }

    GrepTool(SearchBackend searchBackend) {
        this.searchBackend = searchBackend;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String glyph() {
        return ToolGlyphs.INSPECT;
    }

    @Override
    public boolean hasResultView() {
        return true;
    }

    @Override
    public String description() {
        return "Search file contents for a pattern. Returns matching lines with file paths and line numbers. "
                                        + "Respects .gitignore. Output is truncated to 100 matches or 100KB. Long lines are truncated.";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("pattern", "Search pattern (regex or literal string)", true)
                                        .str("path", "Directory or file to search (default: current directory)")
                                        .str("glob", "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'")
                                        .bool("ignoreCase", "Case-insensitive search (default: false)")
                                        .bool("literal", "Treat pattern as literal string instead of regex (default: false)")
                                        .integer("context", "Number of surrounding lines to show before and after each match (default 0)")
                                        .integer("limit", "Maximum number of matches to return (default: 100)")
                                        .number("timeout", "Timeout in seconds before ripgrep is killed (default 30)")
                                        .build();
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pattern = args.getString("pattern");
                if (pattern == null || pattern.isBlank())
                    return ToolResult.error("No pattern provided");

                Path base = Util.resolveDir(sessionCwd);
                Path searchPath = Util.resolveSearchPath(base, args.getString("path"));
                if (!Files.exists(searchPath))
                    return ToolResult.error("Path not found: " + searchPath);

                boolean isDirectory = Files.isDirectory(searchPath);
                String glob = Util.coalesce(args.getString("glob"), args.getString("include"));
                boolean ignoreCase = args.getBoolean("ignoreCase");
                boolean literal = args.getBoolean("literal");
                int context = Math.max(0, args.getInt("context", 0));
                int limit = Math.max(1, args.getInt("limit", DEFAULT_LIMIT));
                long timeoutSeconds = Math.max(1, args.getLong("timeout", DEFAULT_TIMEOUT_SECONDS));

                SearchResult result = searchBackend.search(pattern, searchPath, ignoreCase, literal, glob, limit,
                                                timeoutSeconds);

                if (result.timedOut())
                    return ToolResult.error("ripgrep timed out after " + timeoutSeconds + " seconds");
                if (result.exitCode() != 0 && result.exitCode() != 1) {
                    String err = result.stderr().strip();
                    return ToolResult.error(err.isBlank() ? "ripgrep exited with code " + result.exitCode() : err);
                }

                var matches = new ArrayList<Match>();
                boolean limitReached = false;
                for (String line : result.stdout().split("\\R")) {
                    Match match = parseMatch(line);
                    if (match == null)
                        continue;
                    if (matches.size() >= limit) {
                        limitReached = true;
                        break;
                    }
                    matches.add(match);
                }
                if (matches.isEmpty())
                    return ToolResult.success("No matches found");

                var output = new ArrayList<String>();
                boolean linesTruncated = false;
                for (var match : matches) {
                    if (context == 0) {
                        var truncated = truncateLine(match.text());
                        linesTruncated |= truncated.truncated();
                        output.add(formatPath(searchPath, match.path(), isDirectory) + ":" + match.line() + ": "
                                                        + truncated.text());
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
                if (limitReached)
                    notices.add(limit + " matches limit reached. Use limit=" + (limit * 2)
                                                    + " for more, or refine pattern");
                if (linesTruncated)
                    notices.add("Some lines truncated to " + MAX_LINE_LENGTH + " chars. Use read to see full lines");
                if (sb.length() > MAX_OUTPUT_CHARS) {
                    String truncated = Util.truncateHeadTail(sb.toString(), MAX_OUTPUT_CHARS);
                    sb = new StringBuilder(truncated);
                    notices.add("Output truncated to " + (MAX_OUTPUT_CHARS / 1024) + "KB");
                    details.put("truncated", true);
                    details.put("maxChars", MAX_OUTPUT_CHARS);
                }
                if (limitReached)
                    details.put("matchLimitReached", limit);
                if (linesTruncated)
                    details.put("linesTruncated", true);
                if (!notices.isEmpty())
                    sb.append("\n\n[").append(String.join(". ", notices)).append(']');
                return ToolResult.success(sb.toString(), details);
            } catch (IOException e) {
                if (ProcessRunner.isCommandNotFound(e))
                    return ToolResult.error(RG_NOT_FOUND);
                return ToolResult.error("ripgrep error: " + e.getMessage());
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                Process p = currentProcess;
                if (p != null)
                    ProcessRunner.killTree(p);
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

    private SearchResult processSearch(String pattern, Path searchPath, boolean ignoreCase, boolean literal,
                                    String glob, int limit, long timeoutSeconds) throws IOException {
        var command = new ArrayList<String>();
        command.add("rg");
        command.add("--json");
        command.add("--line-number");
        command.add("--color=never");
        command.add("--hidden");
        if (ignoreCase)
            command.add("--ignore-case");
        if (literal)
            command.add("--fixed-strings");
        if (glob != null && !glob.isBlank()) {
            command.add("--glob");
            command.add(glob);
        }
        command.add("--");
        command.add(pattern);
        command.add(searchPath.toString());

        var result = ProcessRunner.command(command).timeout(Duration.ofSeconds(timeoutSeconds))
                                        .maxOutputBytes(MAX_CAPTURE_BYTES).captureStderr(true)
                                        .onStart(p -> currentProcess = p).run();

        return new SearchResult(result.stdout(), result.stderr(), result.exitCode(), result.timedOut());
    }

    static Match parseMatch(String raw) {
        try {
            JsonNode root = Util.MAPPER.readTree(raw);
            if (!"match".equals(root.path("type").asText()))
                return null;
            JsonNode data = root.path("data");
            String path = data.path("path").path("text").asText(null);
            int line = data.path("line_number").asInt(-1);
            String text = data.path("lines").path("text").asText("").replace("\r\n", "\n").replace("\r", "")
                                            .replaceAll("\n$", "");
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
                if (!relative.toString().startsWith(".."))
                    return relative.toString().replace('\\', '/');
            } catch (IllegalArgumentException ignored) {}
        }
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    static TruncatedLine truncateLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH)
            return new TruncatedLine(line, false);
        return new TruncatedLine(line.substring(0, MAX_LINE_LENGTH - 1) + "…", true);
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        if (result.isError())
            return ToolResultView.of(result.content());
        Map<String, Object> details = result.details();
        Object mc = details.get("matchCount");
        if (mc instanceof Number n)
            return ToolResultView.of(Util.count(n.longValue(), "match", "matches"));
        return ToolResultView.of(result.content());
    }

    record Match(Path path, int line, String text) {
    }
    private record ContextBlock(List<String> lines, boolean truncated) {
    }
    record TruncatedLine(String text, boolean truncated) {
    }
}
