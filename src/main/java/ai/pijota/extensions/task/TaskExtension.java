package ai.pijota.extensions.task;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.HeadlessToolContext;
import ai.pijota.modes.SlashCommand;
import ai.pijota.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskExtension implements Extension {
    private static final int MAX_SUBAGENT_DEPTH = 2;

    // === activity tracking for status bar ===
    record AgentState(String name, int toolCount, int lines, String state, String logFile) {
    }
    private final ConcurrentHashMap<String, AgentState> agentStates = new ConcurrentHashMap<>();
    private final Set<Path> activeLogs = ConcurrentHashMap.newKeySet();
    private ExtensionAPI api;

    private void onActivity(String agent, String line) {
        if (line.startsWith("→ ")) {
            agentStates.compute(agent, (k, v) -> {
                if (v == null)
                    return new AgentState(agent, 1, 0, "running", null);
                return new AgentState(agent, v.toolCount + 1, v.lines, "running", v.logFile);
            });
        } else if (line.startsWith("+")) {
            try {
                int added = Integer.parseInt(line.substring(1));
                agentStates.compute(agent, (k, v) -> {
                    if (v == null)
                        return new AgentState(agent, 0, added, "running", null);
                    return new AgentState(agent, v.toolCount, v.lines + added, "running", v.logFile);
                });
            } catch (NumberFormatException ignored) {}
        } else if (line.startsWith("log: ")) {
            String logPath = line.substring(5).strip();
            activeLogs.add(Path.of(logPath));
            agentStates.compute(agent, (k, v) -> {
                if (v == null)
                    return new AgentState(agent, 0, 0, "running", logPath);
                return new AgentState(agent, v.toolCount, v.lines, "running", logPath);
            });
        }
    }

    private void finished(String agent, boolean success) {
        agentStates.compute(agent, (k, v) -> {
            if (v == null)
                return new AgentState(agent, 0, 0, success ? "done" : "failed", null);
            return new AgentState(agent, v.toolCount, v.lines, success ? "done" : "failed", v.logFile);
        });
    }

    private List<String> statusLines() {
        var lines = new ArrayList<String>();
        for (var state : agentStates.values()) {
            if (!"running".equals(state.state))
                continue;
            String base = String.format("  task:%-13s  %d tools  %d lines  running", state.name, state.toolCount,
                                            state.lines);
            if (state.logFile != null) {
                base += "  " + state.logFile;
            }
            lines.add(base);
        }
        return lines;
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

        api.registerTool(new TaskTool(agents, runtime.llm(), runtime.tools()::enabledToolNames, runtime::systemPrompt,
                                        this::onActivity, this::finished, runtime.cwd(), 0).toDefinition());

        api.registerStatusLines(this::statusLines);
        api.registerSlashCommand(new SlashCommand("logs", "Open the latest sub-agent log", List.of("log", "tail"),
                                        this::openLog));
    }

    @Override
    public void contributeHeadlessTools(HeadlessToolContext ctx) {
        if (!ctx.requested("task") || ctx.depth() >= MAX_SUBAGENT_DEPTH)
            return;
        var agents = SubAgent.loadAll(ctx.cwd());
        if (!agents.isEmpty()) {
            ctx.registry().register(new TaskTool(agents, ctx.llm(), ctx.registry()::enabledToolNames, ctx::systemPrompt,
                                            this::onActivity, this::finished, ctx.cwd(), ctx.depth()));
        }
    }

    private void openLog(String input) {
        var logs = new ArrayList<>(activeLogs);

        Path logDir = Path.of("/tmp", "pijota-logs");
        if (Files.isDirectory(logDir)) {
            try (var stream = Files.newDirectoryStream(logDir, "sub-*.log")) {
                stream.forEach(p -> {
                    if (!logs.contains(p))
                        logs.add(p);
                });
            } catch (IOException ignored) {}
        }

        if (logs.isEmpty()) {
            api.runtime().setStatus("No sub-agent logs yet");
            return;
        }

        logs.sort(Comparator.comparing(TaskExtension::modifiedAt).reversed());

        String query = input == null ? "" : input.trim();
        var target = query.isBlank() || "latest".equalsIgnoreCase(query)
                                        ? logs.getFirst()
                                        : logs.stream().filter(p -> p.getFileName().toString().contains(query)
                                                                        || p.toString().contains(query)).findFirst()
                                                                        .orElse(null);

        if (target == null) {
            api.runtime().setStatus("No match: " + query);
            return;
        }

        try {
            ProcessRunner.command(resolvePager(target.toAbsolutePath().toString())).runInteractive();
        } catch (Exception e) {
            api.runtime().setStatus("Cannot open log: " + e.getMessage());
        }
    }

    private static long modifiedAt(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static List<String> resolvePager(String path) {
        String pager = System.getenv("PAGER");
        if (pager != null && !pager.isBlank()) {
            var args = new ArrayList<>(List.of(pager.split("\\s+")));
            args.add(path);
            return args;
        }
        return List.of("less", "-R", path);
    }
}
