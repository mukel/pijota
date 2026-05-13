package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionRuntime.ExecResult;
import ai.pijota.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TruncatedToolExtension implements Extension {

    private static final int MAX_BYTES = 50 * 1024;
    private static final int MAX_LINES = 2000;

    @Override public String name() { return "truncated-tool"; }
    @Override public String description() { return "Ripgrep tool with proper output truncation"; }

    @Override
    public void init(ExtensionAPI api) {
        var runtime = api.runtime();

        api.registerTool(new Tool.ToolDefinition(
            "rg",
            "Search file contents using ripgrep. Output truncated to " + MAX_LINES + " lines or " + (MAX_BYTES / 1024) + "KB.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "pattern", Map.of("type", "string", "description", "Search pattern (regex)"),
                    "path", Map.of("type", "string", "description", "Directory to search (default: current dir)"),
                    "glob", Map.of("type", "string", "description", "File glob pattern, e.g. *.ts")
                ),
                "required", List.of("pattern")
            ),
            (id, args, cwd) -> {
                String pattern = (String) args.get("pattern");
                String searchPath = (String) args.getOrDefault("path", ".");
                String glob = (String) args.get("glob");

                var cmd = new java.util.ArrayList<>(List.of("rg", "--line-number", "--color=never"));
                if (glob != null) { cmd.add("--glob"); cmd.add(glob); }
                cmd.add(pattern);
                cmd.add(searchPath);

                try {
                    ExecResult result = runtime.exec(cmd);
                    String output = result.stdout();

                    if (!result.success()) {
                        String err = result.stderr().isBlank() ? result.stdout() : result.stderr();
                        return CompletableFuture.completedFuture(
                            Tool.ToolResult.success("No matches found", Map.of("pattern", pattern, "matchCount", 0)));
                    }

                    if (output.isBlank()) {
                        return CompletableFuture.completedFuture(
                            Tool.ToolResult.success("No matches found", Map.of("pattern", pattern, "matchCount", 0)));
                    }

                    String[] allLines = output.split("\\R", -1);
                    int matchCount = 0;
                    for (String line : allLines) if (!line.isBlank()) matchCount++;

                    String text;
                    if (allLines.length > MAX_LINES || output.length() > MAX_BYTES) {
                        int lineIdx = 0, byteCount = 0;
                        var sb = new StringBuilder();
                        for (; lineIdx < Math.min(allLines.length, MAX_LINES); lineIdx++) {
                            String line = allLines[lineIdx];
                            if (byteCount + line.length() + 1 > MAX_BYTES) break;
                            if (lineIdx > 0) sb.append('\n');
                            sb.append(line);
                            byteCount += line.length() + 1;
                        }
                        text = sb.toString();
                        text += "\n\n[Truncated: showing " + lineIdx + " of " + allLines.length
                            + " lines (" + (text.length() / 1024) + "KB of " + (output.length() / 1024) + "KB)]";
                    } else {
                        text = output;
                    }

                    return CompletableFuture.completedFuture(
                        Tool.ToolResult.success(text, Map.of("pattern", pattern, "matchCount", matchCount)));
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(Tool.ToolResult.error("rg failed: " + e.getMessage()));
                }
            }
        ));
    }
}
