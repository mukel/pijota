package org.pijota.tools;

import org.pijota.llm.ToolSchema;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Finds files by glob pattern, answering with paths rather than contents. */
public final class GlobTool implements Tool {
    private static final int DEFAULT_LIMIT = 1000;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private volatile Process currentProcess;
    private final SearchBackend searchBackend;

    /** Injectable search backend so tests can bypass process spawns. */
    @FunctionalInterface
    interface SearchBackend {
        SearchResult search(String pattern, Path searchPath, int limit, long timeoutSeconds) throws IOException;
    }

    public GlobTool() {
        this.searchBackend = this::processSearch;
    }

    GlobTool(SearchBackend searchBackend) {
        this.searchBackend = searchBackend;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String glyph() {
        return ToolGlyphs.INSPECT;
    }

    @Override
    public String description() {
        return "Search for files by glob pattern. Returns matching file paths relative to the search directory. "
                                        + "Respects .gitignore. Output is truncated to 1000 results or 100KB.";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("pattern",
                                        "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'",
                                        true).str("path", "Directory to search in (default: current directory)")
                                        .integer("limit", "Maximum number of results (default: 1000)")
                                        .number("timeout", "Timeout in seconds before file search is killed (default 30)")
                                        .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pattern = args.getString("pattern");
                if (pattern == null || pattern.isBlank())
                    return ToolResult.error("No pattern provided");

                Path base = Util.resolveDir(cwd);
                Path searchPath = Util.resolveSearchPath(base, args.getString("path"));
                if (!Files.exists(searchPath))
                    return ToolResult.error("Path not found: " + searchPath);

                int limit = Math.max(1, args.getInt("limit", DEFAULT_LIMIT));
                long timeout = Math.max(1, args.getLong("timeout", DEFAULT_TIMEOUT_SECONDS));

                SearchResult result = searchBackend.search(pattern, searchPath, limit, timeout);
                if (result.lines().isEmpty())
                    return ToolResult.success("No files found matching pattern: " + pattern);

                boolean limitReached = result.lines().size() >= limit;
                String output = String.join("\n", result.lines());
                if (limitReached)
                    output += "\n\n[" + limit + " results limit reached. Use limit=" + (limit * 2)
                                                    + " for more, or refine pattern]";
                var truncated = Util.truncateToFile(output, Util.DEFAULT_MAX_CHARS, "pijota-find-");

                var details = new LinkedHashMap<String, Object>(truncated.details());
                details.put("backend", result.backend());
                details.put("resultCount", result.lines().size());
                if (limitReached)
                    details.put("resultLimitReached", limit);
                return ToolResult.success(truncated.text(), details);
            } catch (IOException e) {
                return ToolResult.error("Find error: " + e.getMessage());
            }
        });
    }

    @Override
    public void abort() {
        ProcessRunner.abortIfAlive(currentProcess);
    }

    private SearchResult processSearch(String pattern, Path searchPath, int limit, long timeoutSeconds) {
        try {
            SearchResult result = rgSearch(pattern, searchPath, limit, timeoutSeconds);
            if (result == null)
                result = fdSearch(pattern, searchPath, limit, timeoutSeconds);
            if (result == null)
                result = javaSearch(pattern, searchPath, limit);
            return result != null ? result : new SearchResult(List.of(), "java");
        } catch (IOException e) {
            return new SearchResult(List.of(), "java");
        }
    }

    SearchResult fdSearch(String pattern, Path searchPath, int limit, long timeoutSeconds) {
        var command = List.of("fd", "--hidden", "--type", "f", "--glob", "--max-results", Integer.toString(limit), "--",
                                        pattern, searchPath.toString());
        return runFileSearch(command, searchPath, limit, timeoutSeconds, "fd");
    }

    private SearchResult rgSearch(String pattern, Path searchPath, int limit, long timeoutSeconds) {
        var command = List.of("rg", "--files", "--hidden", "--glob", pattern, "--", searchPath.toString());
        return runFileSearch(command, searchPath, limit, timeoutSeconds, "rg");
    }

    /**
     * Runs an external file-finder ({@code fd}/{@code rg}), returning its relativized matches, a timeout marker, or
     * null on non-zero exit / failure so the caller can fall back to the next backend.
     */
    private SearchResult runFileSearch(List<String> command, Path searchPath, int limit, long timeoutSeconds,
                                    String backend) {
        try {
            var result = ProcessRunner.command(command).timeout(Duration.ofSeconds(timeoutSeconds))
                                            .maxOutputBytes(Util.DEFAULT_MAX_CHARS).onStart(p -> currentProcess = p)
                                            .run();
            if (result.timedOut())
                return new SearchResult(List.of("[" + backend + " timed out after " + timeoutSeconds + " seconds]"),
                                                backend);
            if (result.exitCode() != 0)
                return null;
            var lines = result.stdout().lines().filter(s -> !s.isBlank()).map(s -> relativize(searchPath, Path.of(s)))
                                            .limit(limit).toList();
            return new SearchResult(lines, backend);
        } catch (Exception ignored) {
            return null;
        } finally {
            currentProcess = null;
        }
    }

    static SearchResult javaSearch(String pattern, Path searchPath, int limit) throws IOException {
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        int maxDepth = pattern.contains("**") ? 64 : 1;
        var results = new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(searchPath, maxDepth)) {
            stream.filter(Files::isRegularFile).filter(p -> matcher.matches(searchPath.relativize(p))).limit(limit)
                                            .forEach(p -> results.add(relativize(searchPath, p)));
        }
        return new SearchResult(results, "java");
    }

    private static String relativize(Path base, Path path) {
        try {
            Path absolute = path.isAbsolute() ? path : path.toAbsolutePath();
            Path rel = base.toAbsolutePath().normalize().relativize(absolute.normalize());
            return rel.toString().replace('\\', '/');
        } catch (Exception ignored) {
            return path.toString().replace('\\', '/');
        }
    }

    record SearchResult(List<String> lines, String backend) {
    }
}
