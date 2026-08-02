package org.pijota.tools;

import org.pijota.llm.ToolSchema;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class LsTool implements Tool {

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public String glyph() {
        return ToolGlyphs.INSPECT;
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
    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Args args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathStr = Util.resolvePath(args.map(), cwd);
                if (pathStr == null)
                    pathStr = Util.resolveDir(cwd).toString();
                Path path = Path.of(pathStr);
                int limit = Math.max(1, args.getInt("limit", 500));
                if (!Files.isDirectory(path)) {
                    return Tool.ToolResult.error("Not a directory: " + path);
                }

                List<Path> entries;
                try (var stream = Files.list(path)) {
                    entries = stream.limit(limit).sorted().toList();
                }
                if (entries.isEmpty())
                    return Tool.ToolResult.success("(empty directory)", Map.of("count", 0));

                var sb = new StringBuilder();
                for (Path entry : entries)
                    sb.append(entry.getFileName()).append(Files.isDirectory(entry) ? "/" : "").append('\n');
                // count goes in details so the TUI summary reads a field, like grep/glob, with no text re-parsing.
                return Tool.ToolResult.success(sb.toString().stripTrailing(), Map.of("count", entries.size()));
            } catch (IOException e) {
                return Tool.ToolResult.error("Error listing directory: " + e.getMessage());
            }
        });
    }

}
