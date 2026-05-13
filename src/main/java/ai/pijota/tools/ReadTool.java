package ai.pijota.tools;

import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ReadTool implements Tool {

    private static final int MAX_LINES = 2000;

    @Override public String name() { return "read"; }

    @Override
    public String description() {
        return "Read the contents of a file. For text files, output is truncated to 2000 lines or 100KB "
            + "(whichever is hit first). Use offset/limit for large files. "
            + "When you need the full file, continue with offset until complete.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string",
                    "description", "Path to the file to read (relative or absolute)"),
                "offset", Map.of("type", "integer",
                    "description", "Line number to start reading from (1-indexed)"),
                "limit", Map.of("type", "integer",
                    "description", "Maximum number of lines to read")
            ),
            "required", List.of("path"),
            "additionalProperties", false
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(
            String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathStr = Util.resolvePath(args, cwd);
                if (pathStr == null) return ToolResult.error("No path provided");
                Path path = Path.of(pathStr);
                if (!Files.exists(path))
                    return ToolResult.error("File not found: " + path);
                if (Files.isDirectory(path))
                    return ToolResult.error("Path is a directory: " + path);

                var lines = Files.readAllLines(path);
                int totalLines = lines.size();
                int offset = Util.getInt(args, "offset", 1);
                int limit = Util.getInt(args, "limit", MAX_LINES);

                if (totalLines == 0) return ToolResult.success("", Map.of("totalLines", 0));

                int startIdx = Math.max(0, offset - 1);
                if (startIdx >= totalLines) {
                    return ToolResult.error("Offset " + offset
                        + " is beyond end of file (" + totalLines + " lines total)");
                }

                int endIdx = Math.min(totalLines, startIdx + limit);
                var sb = new StringBuilder();
                var details = new LinkedHashMap<String, Object>();
                details.put("offset", offset);
                details.put("limit", limit);
                details.put("totalLines", totalLines);

                for (int i = startIdx; i < endIdx; i++) {
                    sb.append(lines.get(i)).append('\n');
                }

                int shown = endIdx - startIdx;
                if (endIdx < totalLines) {
                    int nextOffset = offset + shown;
                    sb.append("[Showing lines ").append(offset).append("-")
                        .append(offset + shown - 1).append(" of ").append(totalLines)
                        .append(". Use offset=").append(nextOffset)
                        .append(" to continue.]\n");
                    details.put("truncated", true);
                    details.put("nextOffset", nextOffset);
                }

                return ToolResult.success(sb.toString(), details);
            } catch (IOException e) {
                return ToolResult.error("Error reading file: " + e.getMessage());
            }
        });
    }
}
