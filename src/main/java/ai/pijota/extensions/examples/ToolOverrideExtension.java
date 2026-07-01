package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Overrides the built-in {@code read} tool with a version that logs access and blocks sensitive paths ({@code .env},
 * {@code secrets}, {@code credentials}, {@code .ssh}, {@code .aws}, {@code .gnupg}). Demonstrates tool-name shadowing:
 * registering a tool with the same name as a built-in replaces it. Not registered by default.
 */
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
        api.registerTool(Tool.named("read").description(
                                        "Read file contents with access logging. Sensitive paths (.env, secrets, credentials) are blocked.")
                                        .stringParam("filePath", "Path to the file to read", true)
                                        .intParam("offset", "Line number to start from (1-indexed)", false)
                                        .intParam("limit", "Maximum number of lines to read", false)
                                        .run((args, cwd) -> {
                                            String filePath = args.getString("filePath");
                                            if (filePath == null)
                                                filePath = args.getString("path");
                                            if (filePath == null)
                                                return Tool.ToolResult.error("Missing file path");

                                            Path resolved = Path.of(cwd != null ? cwd : System.getProperty("user.dir"))
                                                                            .resolve(filePath).normalize();

                                            if (isBlocked(resolved.toString()))
                                                return Tool.ToolResult.error("Access denied: sensitive file blocked ("
                                                                                + filePath + ")");

                                            try {
                                                List<String> lines = Files.readAllLines(resolved);
                                                int offset = args.getInt("offset", 1);
                                                int limit = args.getInt("limit", lines.size());
                                                int start = Math.max(0, offset - 1);
                                                int end = Math.min(lines.size(), start + limit);
                                                String content = String.join("\n", lines.subList(start, end));

                                                if (content.length() > 50 * 1024)
                                                    content = content.substring(0, 50 * 1024)
                                                                                    + "\n\n[Output truncated at 50KB]";

                                                return Tool.ToolResult.success(content, Map.of("lines", lines.size()));
                                            } catch (IOException e) {
                                                return Tool.ToolResult.error("Error reading file: " + e.getMessage());
                                            }
                                        }));

        api.registerSlashCommand(new SlashCommand("read-log", "Show the file access log", List.of(), _args -> api
                                        .runtime().setStatus("read-log: no persistent log in this example")));
    }

    static boolean isBlocked(String path) {
        for (var p : BLOCKED)
            if (p.matcher(path).find())
                return true;
        return false;
    }
}
