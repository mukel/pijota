package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class ToolOverrideExtension implements Extension {

    private static final Pattern[] BLOCKED = {Pattern.compile("\\.env$"), Pattern.compile("\\.env\\..+$"),
            Pattern.compile("secrets?\\.(json|yaml|yml|toml)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("credentials?\\.(json|yaml|yml|toml)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/\\.ssh/"), Pattern.compile("/\\.aws/"), Pattern.compile("/\\.gnupg/"),};

    @Override
    public String name() {
        return "tool-override";
    }

    @Override
    public String description() {
        return "Overrides read tool with access logging and sensitive path blocking";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerTool(new Tool.ToolDefinition("read",
                                        "Read file contents with access logging. Sensitive paths (.env, secrets, credentials) are blocked.",
                                        Map.of("type", "object", "properties", Map.of("filePath", Map.of("type",
                                                                        "string", "description",
                                                                        "Path to the file to read"), "offset",
                                                                        Map.of("type", "integer", "description",
                                                                                                        "Line number to start from (1-indexed)"),
                                                                        "limit",
                                                                        Map.of("type", "integer", "description",
                                                                                                        "Maximum number of lines to read")),
                                                                        "required", List.of("filePath")),
                                        (id, args, cwd) -> {
                                            String filePath = (String) args.get("filePath");
                                            if (filePath == null)
                                                filePath = (String) args.get("path");
                                            if (filePath == null) {
                                                return CompletableFuture.completedFuture(Tool.ToolResult
                                                                                .error("Missing file path"));
                                            }

                                            Path resolved = Path.of(cwd != null ? cwd : System.getProperty("user.dir"))
                                                                            .resolve(filePath).normalize();

                                            if (isBlocked(resolved.toString())) {
                                                return CompletableFuture.completedFuture(Tool.ToolResult.error(
                                                                                "Access denied: sensitive file blocked ("
                                                                                                                + filePath
                                                                                                                + ")"));
                                            }

                                            try {
                                                List<String> lines = Files.readAllLines(resolved);
                                                int offset = getInt(args, "offset", 1);
                                                int limit = getInt(args, "limit", lines.size());
                                                int start = Math.max(0, offset - 1);
                                                int end = Math.min(lines.size(), start + limit);
                                                String content = String.join("\n", lines.subList(start, end));

                                                if (content.length() > 50 * 1024) {
                                                    content = content.substring(0, 50 * 1024)
                                                                                    + "\n\n[Output truncated at 50KB]";
                                                }

                                                return CompletableFuture.completedFuture(Tool.ToolResult.success(
                                                                                content,
                                                                                Map.of("lines", lines.size())));
                                            } catch (IOException e) {
                                                return CompletableFuture.completedFuture(Tool.ToolResult.error(
                                                                                "Error reading file: " + e.getMessage()));
                                            }
                                        }));

        api.registerSlashCommand(new ai.pijota.modes.SlashCommand("read-log", "Show the file access log", List.of(),
                                        _args -> api.runtime().setStatus(
                                                                        "read-log: no persistent log in this example")));
    }

    private static boolean isBlocked(String path) {
        for (var p : BLOCKED)
            if (p.matcher(path).find())
                return true;
        return false;
    }

    private static int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v instanceof Number n)
            return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
}
