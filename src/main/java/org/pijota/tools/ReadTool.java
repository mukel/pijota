package org.pijota.tools;

import org.pijota.llm.ToolSchema;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    public String glyph() {
        return ToolGlyphs.INSPECT;
    }

    @Override
    public boolean hasResultView() {
        return true;
    }

    @Override
    public String description() {
        return "Read the contents of a file. Output defaults to 2000 lines and is capped at 50KB. "
                                        + "Lines longer than 2000 chars are truncated. Use offset/limit to read specific ranges.";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("path", "Path to the file to read (relative or absolute)", true)
                                        .integer("offset", "Line number to start reading from (1-indexed)")
                                        .integer("limit", "Maximum number of lines to read").build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathStr = Util.resolvePath(args.map(), cwd);
                if (pathStr == null)
                    return ToolResult.error("No path provided");
                Path path = Path.of(pathStr);
                String displayPath = Util.relativePath(path, cwd);
                if (!Files.exists(path))
                    return ToolResult.error("File not found: " + displayPath);
                if (Files.isDirectory(path))
                    return ToolResult.error("Path is a directory: " + displayPath);

                var lines = Files.readAllLines(path);
                return format(lines, args.getInt("offset", 1), args.getInt("limit", DEFAULT_READ_LIMIT));
            } catch (IOException e) {
                return ToolResult.error("Error reading file: " + e.getMessage());
            }
        });
    }

    /**
     * Formats file {@code lines} for tool output: offset/limit slicing, per-line and 50KB byte truncation, and
     * continuation hints. Shared by the local {@code read} tool and the ACP client-backed override.
     */
    public static ToolResult format(java.util.List<String> lines, int offset, int limit) {
        int totalLines = lines.size();
        if (totalLines == 0)
            return ToolResult.success("", Map.of("totalLines", 0));

        int startIdx = Math.max(0, offset - 1);
        int displayOffset = startIdx + 1;
        if (startIdx >= totalLines)
            return ToolResult.error("Offset " + offset + " is beyond end of file (" + totalLines + " lines total)");

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
        details.put("shown", shown);

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
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        if (result.isError())
            return ToolResultView.of(result.content());
        Map<String, Object> details = result.details();
        if (!(details.get("totalLines") instanceof Number tn))
            return ToolResultView.of(result.content());
        int total = tn.intValue();
        int shown = Util.getInt(details, "shown", total);
        int offset = Util.getInt(details, "offset", 1);
        // The request (offset/limit) is already echoed in the call label, so the result reports only what was actually
        // returned: the whole file, or the returned slice of the total. No re-echoing of offset/limit.
        String summary = offset <= 1 && shown >= total
                                        ? total + (total == 1 ? " line" : " lines")
                                        : shown + " of " + total + " lines";
        return ToolResultView.of(summary);
    }

    private static String truncateLine(String line) {
        if (line == null || line.length() <= MAX_LINE_LENGTH)
            return line;
        return line.substring(0, MAX_LINE_LENGTH) + MAX_LINE_SUFFIX;
    }
}
