package ai.pijota.extensions.background;

import ai.pijota.extensions.DeliverAs;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Opt-in background shell jobs (enable with {@code --background}). Adds three tools — {@code bash_background} to launch
 * a long-running command without blocking the turn, {@code bash_output} to poll its output, and {@code bash_kill} to
 * stop it — plus a live panel of running jobs. Unlike {@code --tmux} it needs no external binary (plain JDK process
 * management), so it works everywhere including the native image.
 */
public final class BackgroundJobsExtension implements Extension {

    private static final int MAX_PANEL_ROWS = 6;
    private static final int MAX_WAIT_SECONDS = 120; // cap a blocking bash_output wait
    private static final int CMD_PREVIEW = 60;

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
        var jobs = new BackgroundJobs(completion -> notifyCompletion(api, completion));
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
                                        "Read output from a background job (bash_background) produced since your last read, plus whether"
                                                                        + " it is still running or its exit code. Optional: filter (regex — only matching lines) and"
                                                                        + " timeout (seconds to wait for new output or exit instead of returning immediately).")
                                        .stringParam("id", "The background job id returned by bash_background, e.g. \"bg_1\"",
                                                                        true)
                                        .stringParam("filter", "Optional regex; only output lines matching it are returned",
                                                                        false)
                                        .intParam("timeout", "Optional seconds to wait for new output or exit (default 0 = return now)",
                                                                        false)
                                        .run((args, cwd) -> readOutput(jobs, args));
    }

    /** Reads a job's incremental output, optionally regex-filtered and optionally waiting up to {@code timeout}s. */
    private static ToolResult readOutput(BackgroundJobs jobs, Tool.Args args) {
        String id = args.getString("id");
        if (id == null || id.isBlank())
            return ToolResult.error("No job id provided");
        Pattern filter;
        try {
            String f = args.getString("filter");
            filter = (f == null || f.isBlank()) ? null : Pattern.compile(f);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Invalid filter regex: " + e.getMessage());
        }
        long deadline = System.currentTimeMillis()
                                        + Math.min(Math.max(args.getInt("timeout", 0), 0), MAX_WAIT_SECONDS) * 1000L;

        var raw = new StringBuilder();
        boolean truncated = false;
        while (true) {
            var read = jobs.read(id);
            if (read == null)
                return ToolResult.error("No background job " + id);
            raw.append(read.output());
            truncated |= read.truncated();
            String shown = filtered(raw.toString(), filter);
            if (!shown.isEmpty() || !read.running() || System.currentTimeMillis() >= deadline)
                return format(shown, read.running(), read.exitCode(), truncated, filter);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return format(shown, read.running(), read.exitCode(), truncated, filter);
            }
        }
    }

    private static String filtered(String text, Pattern filter) {
        if (filter == null)
            return text;
        return text.lines().filter(line -> filter.matcher(line).find()).collect(Collectors.joining("\n"));
    }

    private static ToolResult format(String shown, boolean running, Integer exitCode, boolean truncated,
                                    Pattern filter) {
        var body = new StringBuilder(shown);
        if (filter != null && shown.isEmpty())
            body.append("(no lines matching filter yet)");
        if (truncated)
            body.append("\n[output truncated]");
        if (body.length() > 0 && body.charAt(body.length() - 1) != '\n')
            body.append('\n');
        body.append(running ? "[still running]" : "[exited with code " + exitCode + "]");
        return ToolResult.success(body.toString());
    }

    /** Monitor: when a job finishes on its own, tell the agent so it needn't poll — failures steer the run. */
    private static void notifyCompletion(ExtensionAPI api, BackgroundJobs.Completion c) {
        String cmd = c.command().length() > CMD_PREVIEW ? c.command().substring(0, CMD_PREVIEW - 1) + "…" : c.command();
        if (c.exitCode() == 0)
            api.sendUserMessage("⏳ background job " + c.id() + " finished · exit 0 · " + cmd
                                            + " — read its output with bash_output (id=\"" + c.id() + "\").",
                                            DeliverAs.NEXT_TURN);
        else
            api.sendUserMessage("⚠️ background job " + c.id() + " FAILED · exit " + c.exitCode() + " · " + cmd
                                            + " — read its output with bash_output (id=\"" + c.id() + "\").",
                                            DeliverAs.STEER);
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
