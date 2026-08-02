package org.pijota.tools;

import java.util.ArrayList;

import org.pijota.llm.ToolSchema;
import org.pijota.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class WriteTool implements Tool {

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String glyph() {
        return ToolGlyphs.MUTATE;
    }

    @Override
    public boolean hasResultView() {
        return true;
    }

    @Override
    public String description() {
        return "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. "
                                        + "Automatically creates parent directories.";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("path", "Path to the file to write (relative or absolute)", true)
                                        .str("content", "Content to write to the file", true).build();
    }

    @Override
    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Args args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path path = Path.of(Util.resolvePath(args.map(), cwd));
                String content = args.getString("content");

                Path parent = path.getParent();
                if (parent != null)
                    Files.createDirectories(parent);
                FileMutationQueue.run(path, () -> {
                    Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return null;
                });

                long lineCount = content.lines().count();
                // No path here: the tool-call detail already shows the file, so repeating it duplicates the path.
                return Tool.ToolResult.success("Wrote " + content.length() + " bytes (" + lineCount + " lines)",
                                                Map.of("bytes", content.length(), "lines", lineCount, "path",
                                                                                path.toString()));
            } catch (Exception e) {
                return Tool.ToolResult.error("Error writing file: " + e.getMessage());
            }
        });
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        if (result.isError())
            return ToolResultView.of(result.content());
        // See EditTool: the content is the model's copy, the summary is the transcript's, counted like every other
        // tool line ("5 lines"), not "Wrote 133 bytes (5 lines)".
        String summary = result.details().get("lines") instanceof Number n
                                        ? Util.count(n.longValue(), "line", "lines")
                                        : result.content();
        String content = args.getString("content");
        if (content == null || content.isBlank())
            return ToolResultView.of(summary);
        var body = new ArrayList<String>();
        var lines = content.lines().limit(6).toList();
        for (int i = 0; i < lines.size(); i++) {
            if (i >= 5) {
                body.add("… " + (content.lines().count() - 5) + " more lines");
                break;
            }
            body.add(lines.get(i));
        }
        return ToolResultView.of(summary, body);
    }
}
