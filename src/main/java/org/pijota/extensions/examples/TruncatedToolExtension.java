package org.pijota.extensions.examples;

import java.util.ArrayList;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime.ExecResult;
import org.pijota.tools.GrepTool;
import org.pijota.tools.Tool;
import org.pijota.util.ProcessRunner;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Registers an {@code rg} (ripgrep) tool that truncates output to 2000 lines or 50KB, whichever comes first.
 * Demonstrates a properly bounded tool that respects context limits. Not registered by default.
 */
public final class TruncatedToolExtension implements Extension {

    private static final int MAX_BYTES = 50 * 1024;
    private static final int MAX_LINES = 2000;

    @Override
    public String name() {
        return "truncated-tool";
    }

    @Override
    public String description() {
        return "Ripgrep tool with proper output truncation";
    }

    @Override
    public void init(ExtensionAPI api) {
        var runtime = api.runtime();

        api.registerTool(Tool.named("rg")
                                        .description("Search file contents using ripgrep. Output truncated to "
                                                                        + MAX_LINES + " lines or " + (MAX_BYTES / 1024)
                                                                        + "KB.")
                                        .stringParam("pattern", "Search pattern (regex)", true)
                                        .stringParam("path", "Directory to search (default: current dir)", false)
                                        .stringParam("glob", "File glob pattern, e.g. *.ts", false).run((args, cwd) -> {
                                            String pattern = args.getString("pattern");
                                            String searchPath = args.getString("path", ".");
                                            String glob = args.getString("glob");

                                            var cmd = new ArrayList<>(List.of("rg", "--line-number", "--color=never"));
                                            if (glob != null) {
                                                cmd.add("--glob");
                                                cmd.add(glob);
                                            }
                                            cmd.add(pattern);
                                            cmd.add(searchPath);

                                            try {
                                                ExecResult result = runtime.exec(cmd);
                                                String output = result.stdout();

                                                // rg exit codes: 0 = matches, 1 = no matches, >=2 = error; our exec()
                                                // returns -1 when the spawn itself failed (e.g. ripgrep not installed).
                                                // Only 0/1 are real searches — anything else is a failure, not "no
                                                // matches".
                                                if (result.exitCode() < 0 || result.exitCode() >= 2) {
                                                    String stderr = result.stderr() == null
                                                                                    ? ""
                                                                                    : result.stderr().strip();
                                                    // exec() folds a missing-binary IOException into stderr; classify
                                                    // it
                                                    // with the same predicate the throwing callers use.
                                                    String reason = stderr.isBlank() || ProcessRunner.isCommandNotFound(
                                                                                    new IOException(stderr))
                                                                                                                    ? GrepTool.RG_NOT_FOUND
                                                                                                                    : stderr;
                                                    return Tool.ToolResult.error("rg search failed: " + reason);
                                                }

                                                if (output.isBlank())
                                                    return Tool.ToolResult.success("No matches found", Map.of("pattern",
                                                                                    pattern, "matchCount", 0));

                                                String[] allLines = output.split("\\R", -1);
                                                int matchCount = 0;
                                                for (String line : allLines)
                                                    if (!line.isBlank())
                                                        matchCount++;

                                                String text;
                                                if (allLines.length > MAX_LINES || output.length() > MAX_BYTES) {
                                                    int lineIdx = 0, byteCount = 0;
                                                    var sb = new StringBuilder();
                                                    for (; lineIdx < Math.min(allLines.length, MAX_LINES); lineIdx++) {
                                                        String line = allLines[lineIdx];
                                                        if (byteCount + line.length() + 1 > MAX_BYTES)
                                                            break;
                                                        if (lineIdx > 0)
                                                            sb.append('\n');
                                                        sb.append(line);
                                                        byteCount += line.length() + 1;
                                                    }
                                                    text = sb.toString();
                                                    text += "\n\n[Truncated: showing " + lineIdx + " of "
                                                                                    + allLines.length + " lines ("
                                                                                    + (text.length() / 1024) + "KB of "
                                                                                    + (output.length() / 1024) + "KB)]";
                                                } else {
                                                    text = output;
                                                }

                                                return Tool.ToolResult.success(text, Map.of("pattern", pattern,
                                                                                "matchCount", matchCount));
                                            } catch (Exception e) {
                                                return Tool.ToolResult.error("rg failed: " + e.getMessage());
                                            }
                                        }));
    }
}
