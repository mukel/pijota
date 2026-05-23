package ai.pijota.tools;

import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class WriteTool implements Tool {

    @Override
    public String name() { return "write"; }

    @Override
    public String description() {
        return "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. "
            + "Automatically creates parent directories.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string",
                    "description", "Path to the file to write (relative or absolute)"),
                "content", Map.of("type", "string",
                    "description", "Content to write to the file")
            ),
            "required", List.of("path", "content"),
            "additionalProperties", false
        );
    }

    @Override
    public CompletableFuture<Tool.ToolResult> execute(
            String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path path = Path.of(Util.resolvePath(args, cwd));
                String content = (String) args.get("content");

                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
                FileMutationQueue.run(path, () -> {
                    Files.writeString(path, content,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return null;
                });

                long lineCount = content.lines().count();
                return Tool.ToolResult.success(
                    "Wrote " + content.length() + " bytes (" + lineCount + " lines) to " + path,
                    Map.of("bytes", content.length(), "lines", lineCount, "path", path.toString()));
            } catch (IOException e) {
                return Tool.ToolResult.error("Error writing file: " + e.getMessage());
            } catch (Exception e) {
                return Tool.ToolResult.error("Error writing file: " + e.getMessage());
            }
        });
    }

    @Override
    public ToolResultView renderResult(Map<String, Object> args, ToolResult result, String cwd) {
        if (result.isError()) return ToolResultView.of(result.content());
        String summary = result.content();
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) return ToolResultView.of(summary);
        var body = new java.util.ArrayList<String>();
        var lines = content.lines().limit(6).toList();
        for (int i = 0; i < lines.size(); i++) {
            if (i >= 5) { body.add("… " + (content.lines().count() - 5) + " more lines"); break; }
            body.add(lines.get(i));
        }
        return ToolResultView.of(summary, body);
    }
}
