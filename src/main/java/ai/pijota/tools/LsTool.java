package ai.pijota.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import ai.pijota.llm.ToolSchema;
import ai.pijota.util.Util;
import java.util.concurrent.CompletableFuture;

public final class LsTool implements Tool {

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public String glyph() {
        return "📂";
    }

    @Override
    public String description() {
        return "List immediate contents of one directory. Do not use for recursive project exploration; "
                                        + "use glob for finding files across a codebase. Returns entries sorted alphabetically, "
                                        + "with '/' suffix for directories. Includes dotfiles. Output is truncated to 500 entries or 100KB.";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("path", "Directory to list (default: current directory)")
                                        .integer("limit", "Maximum number of entries to return (default: 500)").build();
    }

    @Override
    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathStr = Util.resolvePath(args, cwd);
                if (pathStr == null)
                    pathStr = Util.resolveDir(cwd).toString();
                Path path = Path.of(pathStr);
                int limit = Math.max(1, Util.getInt(args, "limit", 500));
                if (!Files.isDirectory(path)) {
                    return Tool.ToolResult.error("Not a directory: " + path);
                }

                var sb = new StringBuilder();
                try (var stream = Files.list(path).limit(limit)) {
                    stream.sorted().forEach(entry -> {
                        String suffix = Files.isDirectory(entry) ? "/" : "";
                        sb.append(entry.getFileName()).append(suffix).append('\n');
                    });
                }

                if (sb.isEmpty()) {
                    return Tool.ToolResult.success("(empty directory)");
                }
                return Tool.ToolResult.success(sb.toString().stripTrailing());
            } catch (IOException e) {
                return Tool.ToolResult.error("Error listing directory: " + e.getMessage());
            }
        });
    }

}
