package ai.pijota.extensions.task;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.HeadlessToolContext;
import ai.pijota.extensions.UiContext;
import ai.pijota.messages.Message;
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
    private static final long DONE_LINGER_MS = 5000;

    record AgentState(String name, int toolCount, int lines, String state, String lastTool, long doneAt,
                                    String logFile) {
        AgentState withTool(String tool) {
            return new AgentState(name, toolCount + 1, lines, "running", tool, 0, logFile);
        }

        AgentState withLines(int added) {
            return new AgentState(name, toolCount, lines + added, "running", lastTool, 0, logFile);
        }

        AgentState done(boolean success) {
            return new AgentState(name, toolCount, lines, success ? "done" : "failed", lastTool,
                                            System.currentTimeMillis(), logFile);
        }
    }
    private final ConcurrentHashMap<String, AgentState> agentStates = new ConcurrentHashMap<>();
    private final Set<Path> activeLogs = ConcurrentHashMap.newKeySet();
    private ExtensionAPI api;

    private void onActivity(String agent, String line) {
        if (line.startsWith("log: ")) {
            String logPath = line.substring(5).strip();
            activeLogs.add(Path.of(logPath));
            agentStates.compute(agent, (k, v) -> {
                if (v == null)
                    return new AgentState(agent, 0, 0, "running", null, 0, logPath);
                return new AgentState(agent, v.toolCount, v.lines, "running", v.lastTool, 0, logPath);
            });
        } else if (line.startsWith("+")) {
            try {
                int added = Integer.parseInt(line.substring(1));
                agentStates.compute(agent, (k, v) -> {
                    if (v == null)
                        return new AgentState(agent, 0, added, "running", null, 0, null);
                    return v.withLines(added);
                });
            } catch (NumberFormatException ignored) {}
        } else {
            agentStates.compute(agent, (k, v) -> {
                if (v == null)
                    return new AgentState(agent, 1, 0, "running", line, 0, null);
                return v.withTool(line);
            });
        }
    }

    private void finished(String agent, boolean success) {
        agentStates.compute(agent, (k, v) -> {
            if (v == null)
                return new AgentState(agent, 0, 0, success ? "done" : "failed", null, System.currentTimeMillis(), null);
            return v.done(success);
        });
    }

    private List<String> statusLines() {
        pruneDoneStates();
        var lines = new ArrayList<String>();
        for (var state : agentStates.values()) {
            if ("running".equals(state.state)) {
                String activity = state.lastTool != null ? "  " + state.lastTool : "";
                lines.add("⏳" + String.format(" task:%-15s%s  running", state.name, activity));
            } else if (state.doneAt > 0) {
                String icon = "done".equals(state.state) ? "✓" : "✗";
                String summary = String.format(" · %d tools · %d lines", state.toolCount, state.lines);
                lines.add(icon + String.format(" task:%-15s%s", state.name, summary));
            }
        }
        return lines;
    }

    private void pruneDoneStates() {
        long cutoff = System.currentTimeMillis() - DONE_LINGER_MS;
        agentStates.values().removeIf(s -> s.doneAt > 0 && s.doneAt < cutoff);
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
                return String.format("%.1fK", bytes / 1024.0);
            return String.format("%.1fM", bytes / (1024.0 * 1024));
        } catch (IOException e) {
            return "?";
        }
    }

    private String logMeta(Path log) {
        for (var state : agentStates.values()) {
            if (state.logFile != null && log.toString().equals(state.logFile))
                return state.name + " · " + state.toolCount + " tools · " + state.lines + " lines · " + state.state;
        }
        return "";
    }

    private List<Path> collectLogs() {
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
        logs.sort(Comparator.comparing(TaskExtension::modifiedAt).reversed());
        return logs;
    }

    private void openLogFile(Path file) {
        try {
            String strippedFile = stripAnsi(file);
            api.runtime().suspend();
            try {
                ProcessRunner.command(resolvePager(strippedFile)).runInteractive();
            } finally {
                api.runtime().resume();
                try {
                    Files.deleteIfExists(Path.of(strippedFile));
                } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            api.runtime().setStatus("Cannot open log: " + e.getMessage());
        }
    }

    private static String stripAnsi(Path logFile) throws IOException {
        Path stripped = Files.createTempFile("pijota-log-", ".txt");
        try (var reader = Files.newBufferedReader(logFile); var writer = Files.newBufferedWriter(stripped)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int i = 0;
                while (i < line.length()) {
                    if (line.charAt(i) == '\033' && i + 1 < line.length() && line.charAt(i + 1) == '[') {
                        i += 2;
                        while (i < line.length() && !Character.isLetter(line.charAt(i)))
                            i++;
                        i++;
                    } else {
                        writer.write(line.charAt(i));
                        i++;
                    }
                }
                writer.write('\n');
            }
        }
        return stripped.toAbsolutePath().toString();
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
        return List.of("less", path);
    }
}
