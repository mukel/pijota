package ai.pijota.extensions.background;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.tools.Tool;
import ai.pijota.tools.Tool.ToolDefinition;
import ai.pijota.tools.Tool.ToolResult;
import ai.pijota.util.Platform;
import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opt-in background shell jobs (enable with {@code --background}). Adds three tools — {@code bash_background} to launch
 * a long-running command without blocking the turn, {@code bash_output} to poll its output, and {@code bash_kill} to
 * stop it — plus a live panel of running jobs. Unlike {@code --tmux} it needs no external binary (plain JDK process
 * management), so it works everywhere including the native image.
 */
public final class BackgroundJobsExtension implements Extension {

    private static final int MAX_PANEL_ROWS = 6;

    @Override
    public String name() {
        return "background";
    }

    @Override
    public String description() {
        return "Opt-in background shell jobs (bash_background / bash_output / bash_kill). Enable with --background.";
    }

    @Override
    public void init(ExtensionAPI api) {
        if (!api.runtime().getFlag("background", false))
            return;
        var jobs = new BackgroundJobs();
        api.registerTool(startTool(jobs));
        api.registerTool(outputTool(jobs));
        api.registerTool(killTool(jobs));
        api.registerStatusLines(() -> renderPanel(jobs.list()));
    }

    private static ToolDefinition startTool(BackgroundJobs jobs) {
        String shell = Platform.defaultShell();
        String shellFlag = Platform.shellCommandFlag(shell);
        return Tool.named("bash_background").glyph("⏳").description(
                                        "Start a shell command in the background and return immediately with a job id. Use it"
                                                                        + " for long-running work (builds, test suites, dev servers); read its output with"
                                                                        + " bash_output and stop it with bash_kill.")
                                        .stringParam("command", "Shell command to run in the background", true)
                                        .stringParam("workdir", "Working directory (optional; defaults to the session dir)",
                                                                        false)
                                        .run((args, cwd) -> {
                                            String command = args.getString("command");
                                            if (command == null || command.isBlank())
                                                return ToolResult.error("No command provided");
                                            Path workdir = args.has("workdir")
                                                                            ? Path.of(args.getString("workdir"))
                                                                            : Util.resolveDir(cwd);
                                            if (!Files.isDirectory(workdir))
                                                return ToolResult.error("Working directory does not exist: " + workdir);
                                            try {
                                                String id = jobs.start(List.of(shell, shellFlag, command), workdir);
                                                return ToolResult.success("Started background job " + id
                                                                                + " — read with bash_output (id=\"" + id
                                                                                + "\"), stop with bash_kill.",
                                                                                Map.of("jobId", id));
                                            } catch (IOException e) {
                                                return ToolResult.error("Could not start background job: "
                                                                                + e.getMessage());
                                            }
                                        });
    }

    private static ToolDefinition outputTool(BackgroundJobs jobs) {
        return Tool.named("bash_output").glyph("📡").description(
                                        "Read output from a background job (bash_background) produced since your last read, plus"
                                                                        + " whether it is still running or its exit code.")
                                        .stringParam("id", "The background job id returned by bash_background, e.g. \"bg_1\"",
                                                                        true)
                                        .run((args, cwd) -> {
                                            String id = args.getString("id");
                                            if (id == null || id.isBlank())
                                                return ToolResult.error("No job id provided");
                                            var read = jobs.read(id);
                                            if (read == null)
                                                return ToolResult.error("No background job " + id);
                                            var status = read.running()
                                                                            ? "[still running]"
                                                                            : "[exited with code " + read.exitCode()
                                                                                                            + "]";
                                            var body = new StringBuilder(read.output());
                                            if (read.truncated())
                                                body.append("\n[output truncated]");
                                            if (body.length() > 0 && body.charAt(body.length() - 1) != '\n')
                                                body.append('\n');
                                            body.append(status);
                                            return ToolResult.success(body.toString());
                                        });
    }

    private static ToolDefinition killTool(BackgroundJobs jobs) {
        return Tool.named("bash_kill").glyph("⛔")
                                        .description("Terminate a background job started with bash_background.")
                                        .stringParam("id", "The background job id to kill, e.g. \"bg_1\"", true)
                                        .run((args, cwd) -> {
                                            String id = args.getString("id");
                                            if (id == null || id.isBlank())
                                                return ToolResult.error("No job id provided");
                                            return jobs.kill(id)
                                                                            ? ToolResult.success("Killed background job "
                                                                                                            + id)
                                                                            : ToolResult.error("No background job "
                                                                                                            + id);
                                        });
    }

    /** Live footer panel: only running jobs, capped for legibility. */
    static List<String> renderPanel(List<BackgroundJobs.Status> all) {
        var running = all.stream().filter(BackgroundJobs.Status::running).toList();
        if (running.isEmpty())
            return List.of();
        var lines = new ArrayList<String>();
        lines.add("⏳ background · " + running.size() + " running");
        int shown = Math.min(running.size(), MAX_PANEL_ROWS);
        for (int i = 0; i < shown; i++) {
            var status = running.get(i);
            String cmd = status.command().length() > 60 ? status.command().substring(0, 57) + "…" : status.command();
            lines.add("  " + status.id() + "  " + cmd);
        }
        if (running.size() > MAX_PANEL_ROWS)
            lines.add("   … +" + (running.size() - MAX_PANEL_ROWS) + " more");
        return lines;
    }
}
