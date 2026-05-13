package ai.pijota.tools;

import ai.pijota.util.ProcessRunner;
import ai.pijota.util.Util;

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

public final class GlobTool implements Tool {
    private static final int DEFAULT_LIMIT = 1000;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private volatile Process currentProcess;

    @Override public String name() { return "glob"; }

    @Override
    public String description() {
        return "Search for files by glob pattern. Returns matching file paths relative to the search directory. "
            + "Respects .gitignore. Output is truncated to 1000 results or 100KB.";
    }

    @Override public boolean defaultEnabled() { return true; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string",
                    "description", "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'"),
                "path", Map.of("type", "string",
                    "description", "Directory to search in (default: current directory)"),
                "limit", Map.of("type", "integer",
                    "description", "Maximum number of results (default: 1000)"),
                "timeout", Map.of("type", "number",
                    "description", "Timeout in seconds before file search is killed (default 30)")
            ),
            "required", List.of("pattern"),
            "additionalProperties", false
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pattern = (String) args.get("pattern");
                if (pattern == null || pattern.isBlank()) return ToolResult.error("No pattern provided");

                Path base = Util.resolveDir(cwd);
                Path searchPath = Util.resolveSearchPath(base, (String) args.get("path"));
                if (!Files.exists(searchPath)) return ToolResult.error("Path not found: " + searchPath);

                int limit = Math.max(1, Util.getInt(args, "limit", DEFAULT_LIMIT));
                long timeout = Math.max(1, Util.getLong(args, "timeout", DEFAULT_TIMEOUT_SECONDS));

                SearchResult result = rgSearch(pattern, searchPath, limit, timeout);
                if (result == null) result = fdSearch(pattern, searchPath, limit, timeout);
                if (result == null) result = javaSearch(pattern, searchPath, limit);
                if (result.lines().isEmpty()) return ToolResult.success("No files found matching pattern: " + pattern);

                boolean limitReached = result.lines().size() >= limit;
                String output = String.join("\n", result.lines());
                if (limitReached) output += "\n\n[" + limit + " results limit reached. Use limit="
                    + (limit * 2) + " for more, or refine pattern]";
                var truncated = Util.truncateToFile(output, Util.DEFAULT_MAX_CHARS, "pijota-find-");

                var details = new LinkedHashMap<String, Object>(truncated.details());
                details.put("backend", result.backend());
                details.put("resultCount", result.lines().size());
                if (limitReached) details.put("resultLimitReached", limit);
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

    private SearchResult fdSearch(String pattern, Path searchPath, int limit, long timeoutSeconds) {
        try {
            var command = List.of("fd", "--hidden", "--type", "f", "--glob", "--max-results",
                Integer.toString(limit), "--", pattern, searchPath.toString());
            var result = ProcessRunner.command(command)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxOutputBytes(Util.DEFAULT_MAX_CHARS)
                .onStart(p -> currentProcess = p)
                .run();
            if (result.timedOut()) return new SearchResult(List.of("[fd timed out after " + timeoutSeconds + " seconds]"), "fd");
            if (result.exitCode() != 0) return null;
            var lines = result.stdout().lines()
                .filter(s -> !s.isBlank())
                .map(s -> relativize(searchPath, Path.of(s)))
                .limit(limit)
                .toList();
            return new SearchResult(lines, "fd");
        } catch (Exception ignored) {
            return null;
        } finally {
            currentProcess = null;
        }
    }

    private SearchResult rgSearch(String pattern, Path searchPath, int limit, long timeoutSeconds) {
        try {
            var command = new ArrayList<>(List.of("rg", "--files", "--hidden",
                "--glob", pattern, "--", searchPath.toString()));
            var result = ProcessRunner.command(command)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxOutputBytes(Util.DEFAULT_MAX_CHARS)
                .onStart(p -> currentProcess = p)
                .run();
            if (result.timedOut()) return new SearchResult(List.of("[rg timed out after " + timeoutSeconds + " seconds]"), "rg");
            if (result.exitCode() != 0) return null;
            var lines = result.stdout().lines()
                .filter(s -> !s.isBlank())
                .map(s -> relativize(searchPath, Path.of(s)))
                .limit(limit)
                .toList();
            return new SearchResult(lines, "rg");
        } catch (Exception ignored) {
            return null;
        } finally {
            currentProcess = null;
        }
    }

    private static SearchResult javaSearch(String pattern, Path searchPath, int limit) throws IOException {
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        int maxDepth = pattern.contains("**") ? 64 : 1;
        var results = new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(searchPath, maxDepth)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> matcher.matches(searchPath.relativize(p)))
                .limit(limit)
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

    private record SearchResult(List<String> lines, String backend) {}
}
