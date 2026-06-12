package ai.pijota.tools;

import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ReadTool implements Tool {

    private static final int DEFAULT_READ_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_BYTES = 50 * 1024;
    private static final String MAX_LINE_SUFFIX = "... (line truncated to " + MAX_LINE_LENGTH + " chars)";

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "Read the contents of a file. Output defaults to 2000 lines and is capped at 50KB. "
                                        + "Lines longer than 2000 chars are truncated. Use offset/limit to read specific ranges.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string", "description",
                                        "Path to the file to read (relative or absolute)"), "offset",
                                        Map.of("type", "integer", "description",
                                                                        "Line number to start reading from (1-indexed)"),
                                        "limit",
                                        Map.of("type", "integer", "description", "Maximum number of lines to read")),
                                        "required", List.of("path"), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathStr = Util.resolvePath(args, cwd);
                if (pathStr == null)
                    return ToolResult.error("No path provided");
                Path path = Path.of(pathStr);
                String displayPath = Util.relativePath(path, cwd);
                if (!Files.exists(path))
                    return ToolResult.error("File not found: " + displayPath);
                if (Files.isDirectory(path))
                    return ToolResult.error("Path is a directory: " + displayPath);

                var lines = Files.readAllLines(path);
                int totalLines = lines.size();
                int offset = Util.getInt(args, "offset", 1);
                int limit = Util.getInt(args, "limit", DEFAULT_READ_LIMIT);

                if (totalLines == 0)
                    return ToolResult.success("", Map.of("totalLines", 0));

                int startIdx = Math.max(0, offset - 1);
                int displayOffset = startIdx + 1;
                if (startIdx >= totalLines) {
                    return ToolResult.error("Offset " + offset + " is beyond end of file (" + totalLines
                                                    + " lines total)");
                }

                int endIdx = Math.min(totalLines, startIdx + Math.max(0, limit));
                var sb = new StringBuilder();
                var details = new LinkedHashMap<String, Object>();
                details.put("offset", displayOffset);
                details.put("limit", limit);
                details.put("totalLines", totalLines);

                int bytes = 0;
                int shown = 0;
                for (int i = startIdx; i < endIdx; i++) {
                    String line = truncateLine(lines.get(i));
                    int nextBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (shown > 0 && bytes + nextBytes > MAX_BYTES) {
                        details.put("truncated", true);
                        details.put("truncatedBy", "bytes");
                        break;
                    }
                    sb.append(line).append('\n');
                    bytes += nextBytes;
                    shown++;
                }

                if (shown == 0 && startIdx < totalLines) {
                    sb.append("[").append(totalLines - startIdx).append(" more lines in file. Use offset=")
                                                    .append(displayOffset).append(" to continue.]\n");
                    details.put("truncated", true);
                    details.put("nextOffset", displayOffset);
                    return ToolResult.success(sb.toString(), details);
                }

                if (Boolean.TRUE.equals(details.get("truncated")) || startIdx + shown < totalLines) {
                    int nextOffset = displayOffset + shown;
                    sb.append("[Showing lines ").append(displayOffset).append("-").append(displayOffset + shown - 1)
                                                    .append(" of ").append(totalLines).append(". Use offset=")
                                                    .append(nextOffset).append(" to continue.]\n");
                    details.put("truncated", true);
                    details.put("nextOffset", nextOffset);
                }

                return ToolResult.success(sb.toString(), details);
            } catch (IOException e) {
                return ToolResult.error("Error reading file: " + e.getMessage());
            }
        });
    }

    @Override
    public ToolResultView renderResult(Map<String, Object> args, ToolResult result, String cwd) {
        if (result.isError())
            return ToolResultView.of(result.content());
        Map<String, Object> details = result.details();
        Object total = details.get("totalLines");
        if (total instanceof Number n) {
            StringBuilder sb = new StringBuilder(n.intValue() + " line(s)");
            Object off = details.get("offset");
            Object lim = details.get("limit");
            if (off instanceof Number o && o.intValue() > 1)
                sb.append(" [offset=").append(o.intValue()).append("]");
            if (lim instanceof Number l && l.intValue() != DEFAULT_READ_LIMIT)
                sb.append(" [limit=").append(l.intValue()).append("]");
            return ToolResultView.of(sb.toString());
        }
        return ToolResultView.of(result.content());
    }

    private static String truncateLine(String line) {
        if (line == null || line.length() <= MAX_LINE_LENGTH)
            return line;
        return line.substring(0, MAX_LINE_LENGTH) + MAX_LINE_SUFFIX;
    }
}
