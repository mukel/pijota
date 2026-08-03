package org.pijota.extensions.task;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.HeadlessToolContext;
import org.pijota.extensions.UiContext;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.tools.ToolGlyphs;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskExtension implements Extension {
    private static final int MAX_SUBAGENT_DEPTH = 2;
    private static final long DONE_LINGER_MS = 5000;

    record AgentState(String name, int toolCount, int lines, long tokens, String state, String lastTool, long startedAt,
                                    long doneAt, String logFile) {
        static AgentState start(String name) {
            return new AgentState(name, 0, 0, 0, "running", null, System.currentTimeMillis(), 0, null);
        }

        AgentState withTool(String tool) {
            return new AgentState(name, toolCount + 1, lines, tokens, "running", tool, startedAt, 0, logFile);
        }

        AgentState withLines(int added) {
            return new AgentState(name, toolCount, lines + added, tokens, "running", lastTool, startedAt, 0, logFile);
        }

        /** The child's running total, not a delta: a late event cannot double-count or go backwards. */
        AgentState withTokens(long total) {
            return new AgentState(name, toolCount, lines, Math.max(tokens, total), "running", lastTool, startedAt, 0,
                                            logFile);
        }

        AgentState withLog(String log) {
            return new AgentState(name, toolCount, lines, tokens, "running", lastTool, startedAt, 0, log);
        }

        /**
         * The final row keeps whichever total is larger. A stopped or crashed agent reports no tokens, and printing 0
         * under a row that has been counting up to 20K erases spend that really happened.
         */
        AgentState done(boolean success, long tokens) {
            return new AgentState(name, toolCount, lines, Math.max(this.tokens, tokens), success ? "done" : "failed",
                                            lastTool, startedAt, System.currentTimeMillis(), logFile);
        }

        boolean running() {
            return "running".equals(state);
        }
    }

    /** Final per-log summary, kept for {@code /logs} after the live status state is pruned. */
    private record LogSummary(String agent, boolean success, int toolCount, long tokens) {
    }

    private final ConcurrentHashMap<String, AgentState> agentStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LogSummary> finishedLogs = new ConcurrentHashMap<>(); // by log path
    private final Set<Path> activeLogs = ConcurrentHashMap.newKeySet();
    private ExtensionAPI api;
    private TaskTool tool;

    void onActivity(String agent, String line) {
        if (line.startsWith("log: ")) {
            String logPath = line.substring(5).strip();
            activeLogs.add(Path.of(logPath));
            agentStates.compute(agent, (k, v) -> (v == null ? AgentState.start(agent) : v).withLog(logPath));
        } else if (line.equals("detached")) {
            // Backgrounded: it is a job now, and the jobs panel shows it. Leaving the row here too would report one
            // process twice, in two vocabularies.
            agentStates.remove(agent);
        } else if (line.startsWith("tokens: ")) {
            try {
                long total = Long.parseLong(line.substring(8).strip());
                agentStates.compute(agent, (k, v) -> (v == null ? AgentState.start(agent) : v).withTokens(total));
            } catch (NumberFormatException ignored) {}
        } else if (line.startsWith("+")) {
            try {
                int added = Integer.parseInt(line.substring(1));
                agentStates.compute(agent, (k, v) -> (v == null ? AgentState.start(agent) : v).withLines(added));
            } catch (NumberFormatException ignored) {}
        } else {
            agentStates.compute(agent, (k, v) -> (v == null ? AgentState.start(agent) : v).withTool(line));
        }
    }

    void finished(String agent, boolean success, long tokens) {
        var state = agentStates.compute(agent,
                                        (k, v) -> (v == null ? AgentState.start(agent) : v).done(success, tokens));
        if (state.logFile != null) // remember the final summary so /logs can show it after the live state is pruned
            finishedLogs.put(state.logFile, new LogSummary(agent, success, state.toolCount, tokens));
    }

    List<String> statusLines() {
        pruneDoneStates();
        if (agentStates.isEmpty())
            return List.of();
        var lines = new ArrayList<String>();
        long now = System.currentTimeMillis();
        // For a multi-agent run (parallel/chain), lead with an aggregate so progress is legible at a glance.
        if (agentStates.size() > 1) {
            long done = agentStates.values().stream().filter(s -> !s.running()).count();
            long running = agentStates.size() - done;
            long firstStart = agentStates.values().stream().mapToLong(AgentState::startedAt).filter(t -> t > 0).min()
                                            .orElse(now);
            lines.add(String.format(ToolGlyphs.AGENT + " task: %d/%d done · %d running · %s", done, agentStates.size(),
                                            running, Util.formatDuration(now - firstStart)));
        }
        for (var state : agentStates.values()) {
            if (state.running()) {
                String activity = state.lastTool != null ? "  " + state.lastTool : "";
                String progress = state.lines > 0 ? state.lines + " lines · " : "";
                String spend = state.tokens > 0 ? Util.fmtCount(state.tokens) + " tokens · " : "";
                lines.add(ToolGlyphs.AGENT + String.format(" task:%-15s%s  running · %s%s%s", state.name, activity,
                                                spend, progress, Util.formatDuration(now - state.startedAt)));
            } else if (state.doneAt > 0) {
                String icon = "done".equals(state.state) ? ToolGlyphs.DONE : ToolGlyphs.FAILED;
                lines.add(icon + String.format(" task:%-15s · %d tools · %s tokens · %s", state.name, state.toolCount,
                                                Util.fmtCount(state.tokens),
                                                Util.formatDuration(state.doneAt - state.startedAt)));
            }
        }
        return lines;
    }

    /**
     * Keeps finished agents on screen while any sibling is still running (so a parallel batch stays legible), then
     * clears the whole batch once they have all finished and the linger window has elapsed since the last one.
     */
    private void pruneDoneStates() {
        if (agentStates.values().stream().anyMatch(AgentState::running))
            return;
        long latestDone = agentStates.values().stream().mapToLong(AgentState::doneAt).max().orElse(0);
        if (latestDone > 0 && System.currentTimeMillis() - latestDone >= DONE_LINGER_MS)
            agentStates.values().removeIf(s -> s.doneAt > 0);
    }

    // === Extension ===

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "Sub-agent task execution via process isolation";
    }

    @Override
    public void init(ExtensionAPI api) throws Exception {
        this.api = api;
        var runtime = api.runtime();
        var agents = SubAgent.loadAll(runtime.cwd());

        this.tool = new TaskTool(agents, runtime.llm(), runtime.tools()::activeToolNames, runtime::systemPrompt,
                                        this::onActivity, this::finished, runtime.cwd(), 0);
        api.registerTool(tool);

        api.registerStatusLines(this::statusLines);
        api.registerSlashCommand(new SlashCommand("logs", "Open the latest sub-agent log", List.of("log", "tail"),
                                        this::openLog));
        api.registerSlashCommand(new SlashCommand("agents", "List sub-agents, or stop one", List.of("agent", "tasks"),
                                        this::agentsCommand));
    }

    @Override
    public void contributeHeadlessTools(HeadlessToolContext ctx) {
        if (!ctx.requested("task") || ctx.depth() >= MAX_SUBAGENT_DEPTH)
            return;
        var agents = SubAgent.loadAll(ctx.cwd());
        if (!agents.isEmpty()) {
            ctx.registry().register(new TaskTool(agents, ctx.llm(), ctx.registry()::activeToolNames, ctx::systemPrompt,
                                            this::onActivity, this::finished, ctx.cwd(), ctx.depth()));
        }
    }

    /**
     * {@code /agents} lists what is in flight; {@code /agents stop <name>} (or {@code all}) ends it.
     *
     * <p>
     * A sub-agent is a process spending tokens, so it has to be as inspectable and as interruptible as any other job.
     * The status bar shows them while they run and then clears, which is right for a status bar and useless for "what
     * did I just launch, and how do I stop the one that is stuck": both questions need an answer on demand.
     */
    private void agentsCommand(String input) {
        var words = (input == null ? "" : input.trim()).split("\\s+", 2);
        String verb = words[0];
        if (verb.equalsIgnoreCase("stop") || verb.equalsIgnoreCase("kill")) {
            stopAgents(words.length > 1 ? words[1].trim() : "all");
            return;
        }
        if (!verb.isBlank()) {
            api.runtime().setStatus("  /agents · /agents stop <name|all>");
            return;
        }
        listAgents();
    }

    private void stopAgents(String target) {
        if (tool == null)
            return;
        var names = "all".equalsIgnoreCase(target) ? tool.runningNames() : List.of(target);
        int stopped = 0;
        for (String name : names)
            stopped += tool.stop(name);

        if (stopped == 0) {
            var live = tool.runningNames();
            api.runtime().setStatus(live.isEmpty()
                                            ? "  no sub-agents running"
                                            : "  no sub-agent named '" + target + "' · running: "
                                                                            + String.join(", ", live));
            return;
        }
        api.runtime().setStatus("  stopped " + stopped + " sub-agent" + (stopped == 1 ? "" : "s"));
    }

    private void listAgents() {
        var lines = new ArrayList<>(statusLines());
        if (lines.isEmpty()) {
            // Everything has finished and the status bar has cleared: the log summaries are what survives.
            for (var log : collectLogs()) {
                LogSummary done = finishedLogs.get(log.toString());
                if (done != null)
                    lines.add(String.format("%s task:%-15s · %d tools · %s tokens",
                                                    done.success ? ToolGlyphs.DONE : ToolGlyphs.FAILED, done.agent,
                                                    done.toolCount, Util.fmtCount(done.tokens)));
            }
        }
        if (lines.isEmpty()) {
            api.runtime().setStatus("  no sub-agents yet");
            return;
        }
        var sb = new StringBuilder("sub-agents\n").append("\u2500".repeat(40)).append('\n');
        for (String line : lines)
            sb.append("  ").append(line).append('\n');
        sb.append("\n  /agents stop <name|all> \u00b7 /logs to read output\n");
        api.sendMessage(new Message.Custom("agents", sb.toString()));
    }

    private void openLog(String input) {
        var logs = collectLogs();

        if (logs.isEmpty()) {
            api.runtime().setStatus("No sub-agent logs yet");
            return;
        }

        String query = input == null ? "" : input.trim();

        if (query.isBlank()) {
            showLogPicker(logs);
            return;
        }

        if ("latest".equalsIgnoreCase(query)) {
            openLogFile(logs.getFirst());
            return;
        }

        try {
            int idx = Integer.parseInt(query);
            if (idx >= 1 && idx <= logs.size()) {
                openLogFile(logs.get(idx - 1));
                return;
            }
        } catch (NumberFormatException ignored) {}

        var match = logs.stream().filter(p -> p.getFileName().toString().contains(query)).findFirst();
        if (match.isPresent()) {
            openLogFile(match.get());
            return;
        }

        api.runtime().setStatus(String.format("  logs: no match for '%s'. %d log(s) available.", query, logs.size()));
    }

    private void showLogPicker(List<Path> logs) {
        UiContext ui = api.runtime().ui();
        if (ui == null) {
            showStaticLogList(logs);
            return;
        }
        ui.select("logs", logs, log -> String.format("%s  %s  %s", log.getFileName().toString(), formatLogSize(log),
                                        logMeta(log)), this::openLogFile);
    }

    private void showStaticLogList(List<Path> logs) {
        var sb = new StringBuilder();
        sb.append("sub-agent logs\n");
        sb.append("─".repeat(40)).append('\n');
        for (int i = 0; i < logs.size(); i++) {
            Path log = logs.get(i);
            sb.append(String.format("  %d)  %s  %s  %s\n", i + 1, log.getFileName().toString(), formatLogSize(log),
                                            logMeta(log)));
        }
        sb.append(String.format("\n  /logs <n> to select · %d log(s)\n", logs.size()));
        api.sendMessage(new Message.Custom("logs", sb.toString()));
    }

    private String formatLogSize(Path log) {
        try {
            long bytes = Files.size(log);
            if (bytes < 1024)
                return bytes + "B";
            if (bytes < 1024 * 1024)
                return String.format(Locale.ROOT, "%.1fK", bytes / 1024.0);
            return String.format(Locale.ROOT, "%.1fM", bytes / (1024.0 * 1024));
        } catch (IOException e) {
            return "?";
        }
    }

    private String logMeta(Path log) {
        LogSummary done = finishedLogs.get(log.toString());
        if (done != null)
            return String.format("%s · %s · %d tools · %s tokens", done.agent, done.success ? "complete" : "failed",
                                            done.toolCount, Util.fmtCount(done.tokens));
        for (var state : agentStates.values()) {
            if (state.logFile != null && log.toString().equals(state.logFile))
                return state.name + " · running · " + state.toolCount + " tools";
        }
        return "";
    }

    private List<Path> collectLogs() {
        var logs = new ArrayList<>(activeLogs);
        Path logDir = TaskTool.logDir();
        if (Files.isDirectory(logDir)) {
            try (var stream = Files.newDirectoryStream(logDir, "sub-*.log")) {
                stream.forEach(p -> {
                    if (!logs.contains(p))
                        logs.add(p);
                });
            } catch (IOException ignored) {}
        }
        logs.sort(Comparator.comparing(TaskExtension::modifiedAt).reversed());
        return logs;
    }

    private void openLogFile(Path file) {
        UiContext ui = api.runtime().ui();
        if (ui != null) {
            // Live source: the viewer re-reads it and tails as a still-running sub-agent appends to its log.
            ui.viewer(file.getFileName().toString(), () -> readLogLines(file));
            return;
        }
        // Headless: no interactive viewer, post a tail of the log as a message.
        List<String> lines = readLogLines(file);
        int from = Math.max(0, lines.size() - 200);
        var sb = new StringBuilder(file.getFileName().toString()).append('\n');
        for (String line : lines.subList(from, lines.size()))
            sb.append(line).append('\n');
        api.sendMessage(new Message.Custom("logs", sb.toString()));
    }

    /** Reads a log's lines, lenient on non-UTF-8 bytes; returns a one-line notice instead of throwing on I/O error. */
    private static List<String> readLogLines(Path file) {
        try {
            return List.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n", -1));
        } catch (IOException e) {
            return List.of("(cannot read log: " + e.getMessage() + ")");
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

}
