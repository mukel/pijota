package org.pijota.extensions.jobs;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.tools.Tool;
import org.pijota.tools.Tool.ToolDefinition;
import org.pijota.tools.Tool.ToolResult;
import org.pijota.tools.ToolGlyphs;
import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Background shell jobs, on by default ({@code --no-jobs} disables). Adds four tools: {@code jobs_run} to launch a
 * long-running command without blocking the turn, {@code jobs_output} to poll or await its output, {@code jobs_list} to
 * inspect all jobs, and {@code jobs_kill} to stop one (SIGKILL by default, or SIGTERM for graceful shutdown), plus a
 * live panel of running jobs and a completion notice when a job exits. Cross-platform by construction: the command runs
 * through the configured shell (the same resolved shell as the foreground bash tool, bash by default, honouring
 * {@code shell_path}, so it works on Windows with Git Bash/WSL) and lifecycle uses only the Java {@code Process}/
 * {@code ProcessHandle} API, no external {@code kill}/{@code sh}. Unlike {@code --tmux} it needs no external binary, so
 * it works everywhere including the native image.
 */
public final class JobsExtension implements Extension {

    private static final int MAX_PANEL_ROWS = 6;
    private static final int MAX_WAIT_SECONDS = 120; // cap a blocking jobs_output wait
    private static final int CMD_PREVIEW = 60;
    private static final List<String> SUPPORTED_SIGNALS = List.of("SIGKILL", "SIGTERM");

    private final JobRegistry shared;
    private final String shellPath; // the shell jobs_run launches through, the same resolved shell as foreground bash
    private volatile JobRegistry jobs; // bound in init() when enabled; reaped in onShutdown()
    private ExtensionAPI api; // for the /jobs and /kill slash-command handlers to print to the user

    public JobsExtension() {
        this(new JobRegistry());
    }

    /** The registry may be shared with the host app. The TUI adopts user-backgrounded commands into the same one. */
    public JobsExtension(JobRegistry shared) {
        this(shared, Platform.defaultShell());
    }

    /**
     * @param shellPath
     *            the shell {@code jobs_run} launches commands through. Pass {@code settings.effectiveShellPath()} so
     *            background jobs honour the same configured shell (and {@code shell_path}) as the foreground bash tool,
     *            which matters on Windows, where bash may not be on PATH but is set via {@code shell_path}.
     */
    public JobsExtension(JobRegistry shared, String shellPath) {
        this.shared = shared != null ? shared : new JobRegistry();
        this.shellPath = (shellPath == null || shellPath.isBlank()) ? Platform.defaultShell() : shellPath;
    }

    @Override
    public String name() {
        return "jobs";
    }

    @Override
    public boolean enabledByDefault() {
        return true; // on by default: backgrounding a running command (ctrl+b ctrl+b) needs the jobs_* tools
    }

    @Override
    public String description() {
        return "Background shell jobs (jobs_run / jobs_output / jobs_kill), user backgrounding (Ctrl+B twice), and "
                                        + "direct control via /jobs and /kill. Disable with --no-jobs.";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
        jobs = shared;
        jobs.onCompletion(completion -> notifyCompletion(api, completion));
        api.registerTool(startTool(jobs, shellPath));
        api.registerTool(outputTool(jobs));
        api.registerTool(listTool(jobs));
        api.registerTool(killTool(jobs));
        api.registerStatusLines(() -> renderPanel(jobs.list()));
        // Direct user control (no model round-trip): mechanical actions get commands, language stays for intent.
        api.registerSlashCommand(new SlashCommand("jobs", "List background jobs (id, status, command)", List.of(),
                                        a -> showJobs()));
        api.registerSlashCommand(new SlashCommand("kill", "Stop a background job", "<id> [SIGTERM]", List.of(),
                                        this::killCommand));
    }

    /** {@code /jobs}, the same listing the agent's jobs_list produces, shown to the user (not sent to the model). */
    private void showJobs() {
        api.sendMessage(new Message.Custom("jobs", renderList(jobs.list())));
    }

    /** {@code /kill <id> [signal]}: terminate a job directly; reuses the jobs_kill body. */
    private void killCommand(String args) {
        String[] parts = args == null ? new String[0] : args.strip().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            api.sendMessage(new Message.Custom("jobs", "Usage: /kill <id> [SIGTERM]"));
            return;
        }
        String signal = parts.length > 1 ? parts[1] : "SIGKILL";
        api.sendMessage(new Message.Custom("jobs", killJob(jobs, parts[0], signal).content()));
    }

    /** Reap any still-running background jobs on graceful shutdown (extension lifecycle, not a raw JVM hook). */
    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        if (jobs != null)
            jobs.killAll();
    }

    // -- jobs_run --

    private static ToolDefinition startTool(JobRegistry jobs, String shell) {
        String shellFlag = Platform.shellCommandFlag(shell);
        return Tool.named("jobs_run").glyph(ToolGlyphs.RUN).description(
                                        "Start a shell command in the background and return immediately with a job id. Use it"
                                                                        + " for long-running work (builds, test suites, dev servers); read its output with"
                                                                        + " jobs_output and stop it with jobs_kill.")
                                        .stringParam("command", "Shell command to run in the background", true)
                                        .stringParam("workdir", "Working directory (optional; defaults to the session dir)",
                                                                        false)
                                        .stringParam("name", "Human-readable label for this job (shown in jobs_list / panel)",
                                                                        false)
                                        .mapParam("env", "Environment variables to set for this job (e.g. {CI: \"true\"})",
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
                                                String id = jobs.start(List.of(shell, shellFlag, command), workdir,
                                                                                args.getString("name"), envOf(args));
                                                return ToolResult.success("Started background job " + id
                                                                                + " · read with jobs_output (id=\"" + id
                                                                                + "\"), stop with jobs_kill.",
                                                                                Map.of("jobId", id));
                                            } catch (IOException e) {
                                                if (ProcessRunner.isCommandNotFound(e))
                                                    return ToolResult.error("Couldn't start the shell (" + shell
                                                                                    + "). On Windows, install Git Bash or WSL, or set"
                                                                                    + " shell_path in settings.");
                                                return ToolResult.error("Could not start background job: "
                                                                                + e.getMessage());
                                            }
                                        });
    }

    /** Optional env map from the raw args, stringified; null when absent. */
    private static Map<String, String> envOf(Tool.Args args) {
        if (!(args.get("env") instanceof Map<?, ?> raw) || raw.isEmpty())
            return null;
        var env = new LinkedHashMap<String, String>();
        raw.forEach((k, v) -> env.put(String.valueOf(k), String.valueOf(v)));
        return env;
    }

    // -- jobs_output --

    private static ToolDefinition outputTool(JobRegistry jobs) {
        return Tool.named("jobs_output").glyph(ToolGlyphs.INSPECT).description(
                                        "Read a background job's output produced since your last read, plus whether it is still running"
                                                                        + " or its exit code. Optional: filter (regex, only matching lines), timeout (seconds to"
                                                                        + " wait for new output or exit), and wait_for_completion (block until the job exits).")
                                        .stringParam("id", "The background job id returned by jobs_run, e.g. \"bg_1\"",
                                                                        true)
                                        .stringParam("filter", "Optional regex; only output lines matching it are returned",
                                                                        false)
                                        .intParam("timeout", "Optional seconds to wait for new output or exit (default 0 = return now)",
                                                                        false)
                                        .boolParam("wait_for_completion",
                                                                        "Block until the job exits (max 120s); overrides timeout",
                                                                        false)
                                        .run((args, cwd) -> readOutput(jobs, args));
    }

    /**
     * Polls one job, accumulating the output it produces (so a job that finishes between polls never loses its tail),
     * until it exits, the deadline passes, or, unless waiting for completion, it has matching output to show.
     */
    private static ToolResult readOutput(JobRegistry jobs, Tool.Args args) {
        String id = args.getString("id");
        if (isBlank(id))
            return ToolResult.error("No job id provided");
        Pattern filter;
        try {
            String f = args.getString("filter");
            filter = (f == null || f.isBlank()) ? null : Pattern.compile(f);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Invalid filter regex: " + e.getMessage());
        }
        boolean waitCompletion = args.getBoolean("wait_for_completion");
        long seconds = waitCompletion
                                        ? MAX_WAIT_SECONDS
                                        : Math.min(Math.max(args.getInt("timeout", 0), 0), MAX_WAIT_SECONDS);
        long deadline = System.currentTimeMillis() + seconds * 1000L;

        var acc = new StringBuilder();
        JobRegistry.Read read = jobs.read(id);
        if (read == null)
            return ToolResult.error("No background job " + id);
        while (true) {
            acc.append(read.output());
            boolean ready = !waitCompletion && !filtered(acc.toString(), filter).isEmpty();
            // read.drained(), not just !running(): a short-lived command exits while its output is still in the pipe,
            // and stopping at exit reported "(no lines matching filter yet)" for output that was already written. The
            // model then believes the pattern is absent. The deadline still bounds the wait.
            if ((!read.running() && read.drained()) || ready || System.currentTimeMillis() >= deadline)
                break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            read = jobs.read(id);
        }

        String shown = filtered(acc.toString(), filter);
        var body = new StringBuilder(shown);
        if (filter != null && shown.isEmpty())
            body.append("(no lines matching filter yet)");
        if (read.truncated())
            body.append("\n[output truncated]");
        if (body.length() > 0 && body.charAt(body.length() - 1) != '\n')
            body.append('\n');
        body.append(statusLine(read));
        return ToolResult.success(body.toString(), statusDetails(read));
    }

    private static String statusLine(JobRegistry.Read read) {
        return read.running() ? "[still running]" : "[exited with code " + read.exitCode() + "]";
    }

    private static Map<String, Object> statusDetails(JobRegistry.Read read) {
        var d = new LinkedHashMap<String, Object>();
        d.put("id", read.id());
        d.put("running", read.running());
        if (!read.running())
            d.put("exitCode", read.exitCode());
        d.put("outputChars", read.outputChars());
        d.put("truncated", read.truncated());
        return d;
    }

    private static String filtered(String text, Pattern filter) {
        if (filter == null)
            return text;
        return text.lines().filter(line -> filter.matcher(line).find()).collect(Collectors.joining("\n"));
    }

    // -- jobs_list / jobs_kill --

    private static ToolDefinition listTool(JobRegistry jobs) {
        return Tool.named("jobs_list").glyph(ToolGlyphs.AGENT).description(
                                        "List every background job (running and finished) with its id, status/exit code, output size,"
                                                                        + " and command, so you can find a job id to read with jobs_output or stop with jobs_kill.")
                                        .run((args, cwd) -> ToolResult.success(renderList(jobs.list())));
    }

    /** One line per job: id, status (running / exit N), output size, optional label, optional [truncated], command. */
    static String renderList(List<JobRegistry.Status> all) {
        if (all.isEmpty())
            return "(no background jobs)";
        var sb = new StringBuilder();
        for (var s : all) {
            String status = s.running() ? "running" : "exit " + s.exitCode();
            sb.append(s.id()).append("  ").append(status).append("  ").append(Util.formatSize(s.outputChars()));
            if (hasDistinctName(s.name(), s.command()))
                sb.append("  ").append(s.name());
            if (s.truncated())
                sb.append("  [truncated]");
            sb.append("  ").append(s.command()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static ToolDefinition killTool(JobRegistry jobs) {
        return Tool.named("jobs_kill").glyph(ToolGlyphs.RUN).description(
                                        "Stop a background job by signalling its whole process tree. Defaults to SIGKILL (force);"
                                                                        + " pass signal=SIGTERM to ask it to shut down gracefully first.")
                                        .stringParam("id", "The background job id to stop, e.g. \"bg_1\"", true)
                                        .enumParam("signal", "SIGKILL (force, default) or SIGTERM (graceful)", false,
                                                                        SUPPORTED_SIGNALS)
                                        .run((args, cwd) -> killJob(jobs, args.getString("id"),
                                                                        args.getString("signal", "SIGKILL")));
    }

    /** Stops a job by id with an optional signal, shared by the jobs_kill tool and the /kill slash command. */
    static ToolResult killJob(JobRegistry jobs, String id, String signal) {
        if (isBlank(id))
            return ToolResult.error("No job id provided");
        String sig = (signal == null ? "SIGKILL" : signal).toUpperCase();
        if (!SUPPORTED_SIGNALS.contains(sig))
            return ToolResult.error("Unsupported signal: " + sig + ". Supported: " + SUPPORTED_SIGNALS);
        boolean force = sig.equals("SIGKILL");
        if (!jobs.stop(id, force))
            return ToolResult.error("No background job " + id);
        return ToolResult.success((force ? "Killed" : "Sent SIGTERM to") + " background job " + id);
    }

    // -- completion monitor + panel --

    /**
     * Monitor: when a job finishes on its own, tell the agent so it needn't poll. Single-arg delivery steers the run if
     * the agent is busy and wakes it if idle, so a completion (especially a failure) is never silently lost while the
     * agent sits awaiting input.
     */
    private static void notifyCompletion(ExtensionAPI api, JobRegistry.Completion c) {
        String label = hasDistinctName(c.name(), c.command()) ? c.name() + " · " : "";
        String tail = " · read its output with jobs_output (id=\"" + c.id() + "\").";
        String msg = c.exitCode() == 0
                                        ? ToolGlyphs.DONE + " background job " + c.id() + " finished · exit 0 · "
                                                                        + label
                                                                        + Util.ellipsize(c.command(), CMD_PREVIEW)
                                                                        + tail
                                        : ToolGlyphs.FAILED + " background job " + c.id() + " failed · exit "
                                                                        + c.exitCode() + " · " + label
                                                                        + Util.ellipsize(c.command(), CMD_PREVIEW)
                                                                        + tail;
        api.sendUserMessage(msg);
    }

    /** Live footer panel: only running jobs, capped for legibility. */
    static List<String> renderPanel(List<JobRegistry.Status> all) {
        var running = all.stream().filter(JobRegistry.Status::running).toList();
        if (running.isEmpty())
            return List.of();
        var lines = new ArrayList<String>();
        lines.add(ToolGlyphs.RUN + " background · " + running.size() + " running");
        int shown = Math.min(running.size(), MAX_PANEL_ROWS);
        for (int i = 0; i < shown; i++) {
            var s = running.get(i);
            String display = hasDistinctName(s.name(), s.command()) ? s.name() : s.command();
            lines.add("  " + s.id() + "  " + Util.ellipsize(display, CMD_PREVIEW));
        }
        if (running.size() > MAX_PANEL_ROWS)
            lines.add("   … +" + (running.size() - MAX_PANEL_ROWS) + " more");
        return lines;
    }

    // -- small shared helpers --

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** True when a job's name is a meaningful label distinct from its raw command. */
    private static boolean hasDistinctName(String name, String command) {
        return name != null && !name.equals(command);
    }

}
