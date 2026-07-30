package org.pijota.extensions.task;

import org.pijota.agent.SubAgentLauncher;
import org.pijota.llm.LlmClient;
import org.pijota.llm.ModelRegistry;
import org.pijota.tools.Tool;
import org.pijota.util.Env;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class TaskTool implements Tool {
    private static final int MAX_CONCURRENCY = 4;
    private static final int OUTPUT_PREVIEW_LINES = 12;

    private final List<SubAgent> agents;
    private final LlmClient llm;
    private final Supplier<List<String>> enabledToolNames;
    private final Supplier<String> systemPrompt;
    private final BiConsumer<String, String> activityHandler;
    private final FinishHandler finishedHandler;
    private final Path cwd;

    /** Notified when a sub-agent finishes, with its success flag and total token usage. */
    @FunctionalInterface
    public interface FinishHandler {
        void finished(String agent, boolean success, long tokens);
    }
    private final int depth;
    private final Map<String, List<Map<String, Object>>> chainConfigs;
    private final AtomicInteger logCounter = new AtomicInteger();

    /**
     * How one sub-agent is executed — spawns a child JVM in production; injectable so tests drive the orchestration
     * (single/parallel/chain, aggregation, worktrees) against canned results without spawning processes.
     */
    interface AgentRunner {
        SubAgentResult run(SubAgent sub, String task, String workDir);
    }

    private AgentRunner agentRunner = this::spawnAgent;

    /** Test seam: replace the real process-spawning runner with a fake one. */
    void setAgentRunner(AgentRunner runner) {
        this.agentRunner = runner;
    }

    public TaskTool(List<SubAgent> agents, LlmClient llm, Supplier<List<String>> enabledToolNames,
                                    Supplier<String> systemPrompt, BiConsumer<String, String> activityHandler,
                                    FinishHandler finishedHandler, Path cwd, int depth) {
        this.agents = agents;
        this.llm = llm;
        this.enabledToolNames = enabledToolNames;
        this.systemPrompt = systemPrompt;
        this.activityHandler = activityHandler;
        this.finishedHandler = finishedHandler;
        this.cwd = cwd;
        this.depth = depth;
        this.chainConfigs = loadChainConfigs();
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String glyph() {
        return "⚙";
    }

    @Override
    public String description() {
        return "Launch specialized sub-agents to handle complex, multi-step tasks autonomously. "
                                        + "Modes: single (agent + description), parallel (tasks array), chain (sequential with {previous} placeholder). "
                                        + (agents.isEmpty() ? "" : "Available agents: " + agentList());
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("agent", Map.of("type", "string", "description",
                                        "Name of the agent to invoke (for single mode)"), "description",
                                        Map.of("type", "string", "description",
                                                                        "Detailed task description for the sub-agent (single mode)"),
                                        "subagent_type",
                                        Map.of("type", "string", "description",
                                                                        "Type of subagent: explore or general (single mode). Explore is fast for codebase searches, general for complex tasks."),
                                        "tasks",
                                        Map.of("type", "array", "description",
                                                                        "Array of {agent, description} for parallel execution",
                                                                        "items",
                                                                        Map.of("type", "object", "properties", Map.of(
                                                                                                        "agent",
                                                                                                        Map.of("type", "string", "description",
                                                                                                                                        "Agent name"),
                                                                                                        "description",
                                                                                                        Map.of("type", "string", "description",
                                                                                                                                        "Task description")),
                                                                                                        "required",
                                                                                                        List.of("agent", "description"))),
                                        "chain",
                                        Map.of("type", "array", "description",
                                                                        "Array of {agent, description} for sequential execution. Use {previous} placeholder in descriptions to reference prior output.",
                                                                        "items",
                                                                        Map.of("type", "object", "properties", Map.of(
                                                                                                        "agent",
                                                                                                        Map.of("type", "string", "description",
                                                                                                                                        "Agent name"),
                                                                                                        "description",
                                                                                                        Map.of("type", "string", "description",
                                                                                                                                        "Task with optional {previous} placeholder")),
                                                                                                        "required",
                                                                                                        List.of("agent", "description"))),
                                        "command",
                                        Map.of("type", "string", "description",
                                                                        "The command that triggered this task")),
                                        "required", List.of());
    }

    private String agentList() {
        return agents.stream().map(SubAgent::name).reduce((a, b) -> a + ", " + b).orElse("none");
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String sessionCwd) {
        var m = args.map();
        return switch (detectMode(m)) {
            case "chain" -> executeChain(m);
            case "parallel" -> executeParallel(m);
            default -> executeSingle(m);
        };
    }

    private String detectMode(Map<String, Object> args) {
        if (args.get("chain") instanceof List<?> c && !c.isEmpty())
            return "chain";
        if (args.get("tasks") instanceof List<?> t && !t.isEmpty())
            return "parallel";
        return "single";
    }

    // --- single mode ---

    private CompletableFuture<ToolResult> executeSingle(Map<String, Object> args) {
        String agentName = (String) args.getOrDefault("agent", "");
        String subagentType = (String) args.getOrDefault("subagent_type", "");
        String description = (String) args.getOrDefault("description", args.getOrDefault("instructions", ""));

        String name = agentName.isBlank() && !subagentType.isBlank() ? subagentType : agentName;

        var agent = agents.stream().filter(a -> a.name().equalsIgnoreCase(name)).findFirst();
        if (agent.isEmpty()) {
            return CompletableFuture.completedFuture(ToolResult
                                            .error("Sub-agent not found: " + name + ". Available: " + agentList()));
        }

        SubAgentResult result = runAgent(agent.get(), description);
        if (result.error != null) {
            return CompletableFuture.completedFuture(new ToolResult(result.formattedOutput(), true,
                                            detailsForResults("single", List.of(result))));
        }

        return CompletableFuture.completedFuture(ToolResult.success(result.formattedOutput(),
                                        detailsForResults("single", List.of(result))));
    }

    // --- parallel mode ---

    @SuppressWarnings("unchecked")
    private CompletableFuture<ToolResult> executeParallel(Map<String, Object> args) {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) args.get("tasks");
        if (tasks == null || tasks.isEmpty()) {
            return CompletableFuture.completedFuture(ToolResult.error("No tasks provided for parallel mode"));
        }

        boolean useWorktree = resolveWorktree(args);

        var results = new ArrayList<SubAgentResult>();
        var pool = Executors.newFixedThreadPool(Math.min(tasks.size(), MAX_CONCURRENCY));
        var futures = new ArrayList<CompletableFuture<Void>>();
        var worktrees = new ArrayList<Path>();

        int idx = 0;
        for (var task : tasks) {
            final int taskIdx = idx++;
            futures.add(CompletableFuture.runAsync(() -> {
                String agentId = (String) task.getOrDefault("agent", "");
                String desc = (String) task.getOrDefault("description", task.getOrDefault("instructions", ""));
                var agent = agents.stream().filter(a -> a.name().equalsIgnoreCase(agentId)).findFirst();
                SubAgentResult result;
                if (agent.isPresent()) {
                    String wd = cwd.toString();
                    if (useWorktree) {
                        Path wt = createWorktree(agent.get().name(), taskIdx);
                        if (wt != null) {
                            synchronized (worktrees) {
                                worktrees.add(wt);
                            }
                            wd = wt.toString();
                        }
                    }
                    result = agentRunner.run(agent.get(), desc, wd);
                } else {
                    result = new SubAgentResult(agentId, "", 1, 0, 0, null,
                                                    "Unknown agent: " + agentId + ". Available: " + agentList());
                }
                synchronized (results) {
                    results.add(result);
                }
            }, pool));
        }

        int launched = futures.size();
        try {
            // A bit beyond the per-agent 5-minute cap so each agent's own timeout fires first and yields a proper
            // "timed out" result, rather than this overall net abandoning it with no record.
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(6, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // Per-agent failures/timeouts already surface as their own results below; anything still unfinished here is
            // reported as "did not complete" so the count is never silently wrong.
        } finally {
            pool.shutdownNow();
            for (var wt : worktrees)
                deleteWorktree(wt);
        }

        results.sort(Comparator.comparing(SubAgentResult::agent));
        return CompletableFuture.completedFuture(ToolResult.success(formatParallelReport(results, launched),
                                        detailsForResults("parallel", results)));
    }

    /** The parallel run's rendered report: a one-line-per-agent summary block followed by each agent's detail. */
    static String formatParallelReport(List<SubAgentResult> results, int launched) {
        int success = (int) results.stream().filter(r -> r.exitCode == 0).count();
        int incomplete = launched - results.size();
        var sb = new StringBuilder();
        sb.append("Parallel: ").append(success).append("/").append(launched).append(" succeeded");
        if (incomplete > 0)
            sb.append(" · ").append(incomplete).append(" did not complete (timed out)");
        sb.append("\n\n");
        for (var r : results) {
            String icon = r.exitCode == 0 ? "(ok)" : "(failed)";
            sb.append("  ").append(r.agent).append(" ").append(icon).append("  ").append(r.turns).append(" turn(s)")
                                            .append(", ").append(r.toolCount).append(" tool(s)\n");
        }
        sb.append("\n");
        for (var r : results) {
            sb.append("─── ").append(r.agent);
            if (r.exitCode != 0)
                sb.append("  FAILED");
            sb.append(" ───\n");
            sb.append(r.formattedOutput()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    // --- chain mode ---

    @SuppressWarnings("unchecked")
    private CompletableFuture<ToolResult> executeChain(Map<String, Object> args) {
        List<Map<String, Object>> chain = (List<Map<String, Object>>) args.get("chain");
        if (chain == null || chain.isEmpty()) {
            return CompletableFuture.completedFuture(ToolResult.error("No chain steps provided"));
        }

        // Resolve chain preset name: if first element is a string, look up config
        var resolved = resolveChain(chain);
        if (resolved == null) {
            return CompletableFuture.completedFuture(ToolResult.error("Chain not found: " + chain.getFirst()
                                            + ". Available: " + chainConfigs.keySet()));
        }
        chain = resolved;

        var results = new ArrayList<SubAgentResult>();
        String previousOutput = "";

        for (int i = 0; i < chain.size(); i++) {
            var step = chain.get(i);
            String agentId = (String) step.getOrDefault("agent", "");
            String desc = (String) step.getOrDefault("description", step.getOrDefault("instructions", ""));
            desc = desc.replace("{previous}", previousOutput);

            var agent = agents.stream().filter(a -> a.name().equalsIgnoreCase(agentId)).findFirst();
            if (agent.isEmpty()) {
                results.add(new SubAgentResult(agentId, "", 1, 0, 0, null,
                                                "Unknown agent: " + agentId + ". Available: " + agentList()));
                break;
            }

            SubAgentResult result = runAgent(agent.get(), desc);
            results.add(result);
            if (result.exitCode != 0) {
                return CompletableFuture.completedFuture(new ToolResult(
                                                "Chain stopped at step " + (i + 1) + " (" + result.agent + "):\n"
                                                                                + result.formattedOutput(),
                                                true, detailsForResults("chain", results)));
            }
            previousOutput = result.output;
        }

        return CompletableFuture.completedFuture(ToolResult.success(results.getLast().formattedOutput(),
                                        detailsForResults("chain", results)));
    }

    // --- process-based agent execution ---

    private SubAgentResult runAgent(SubAgent sub, String task) {
        return agentRunner.run(sub, task, cwd.toString());
    }

    /**
     * The model a sub-agent should run on, or {@code null} to inherit the parent's. A per-agent {@code model:} override
     * wins; otherwise {@code -Dpijota.subagent.model} / {@code PIJOTA_SUBAGENT_MODEL} routes all sub-agents to a single
     * (typically cheaper) model. The model must be reachable with the current provider's credentials.
     */
    static String subAgentModel(SubAgent sub) {
        if (sub.model() != null && !sub.model().isBlank())
            return sub.model();
        return Env.get("pijota.subagent.model", "PIJOTA_SUBAGENT_MODEL");
    }

    /**
     * Resolve the dynamic, read-only {@code alias/current} to the parent's live {@code provider/model}, so a sub-agent
     * can explicitly mirror the model the session is on right now. Static aliases and concrete models pass through
     * unchanged (the child resolves those itself).
     */
    static String resolveCurrentAlias(String declared, String currentProvider, String currentModel) {
        String currentSpec = ModelRegistry.ALIAS_PROVIDER + "/" + ModelRegistry.CURRENT_ALIAS;
        if (currentSpec.equalsIgnoreCase(declared) && currentProvider != null && currentModel != null)
            return currentProvider + "/" + currentModel;
        return declared;
    }

    private SubAgentResult spawnAgent(SubAgent sub, String task, String workDir) {
        Path logFile;
        BufferedWriter logWriter;
        try {
            List<String> toolNames = new ArrayList<>();
            boolean allowAll = sub.tools() == null || sub.tools().isEmpty();
            for (var toolName : enabledToolNames.get()) {
                if (allowAll || sub.tools().contains(toolName))
                    toolNames.add(toolName);
            }

            String subPrompt = systemPrompt.get() + "\n\n# SUB-AGENT: " + sub.name() + "\n" + sub.systemPrompt()
                                            + "\n\n# TASK\n" + task;

            logFile = createLogFile(sub.name());
            logWriter = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            logWriter.write("─── " + sub.name() + " ───\n");
            logWriter.write("task: " + task + "\n\n");
            logWriter.flush();
            fireActivity(sub.name(), "log: " + logFile);

            var taskJson = new LinkedHashMap<String, Object>();
            taskJson.put("description", task);
            taskJson.put("systemPrompt", subPrompt);
            taskJson.put("tools", toolNames);
            taskJson.put("cwd", workDir);
            taskJson.put("subagentDepth", depth + 1);
            taskJson.put("logFile", logFile.toString());
            taskJson.put("parentLogs", true);
            String p = llm.provider();
            String m = llm.model();
            // Model routing: a per-agent override wins; otherwise an optional global sub-agent model lets the main
            // loop stay on a strong model while sub-agents run on a cheaper one; otherwise inherit the parent model.
            String subModel = resolveCurrentAlias(subAgentModel(sub), p, m);
            if (p != null)
                taskJson.put("provider", p);
            if (m != null && subModel == null)
                taskJson.put("model", m);
            String parentApiKey = llm.apiKey();
            if (parentApiKey != null && !parentApiKey.isBlank()) {
                taskJson.put("apiKey", parentApiKey);
            }

            var procArgs = SubAgentLauncher.agentCommand(cwd.toString());
            procArgs.add("--agent");
            procArgs.add("--no-session");
            if (subModel != null) {
                procArgs.add("--model");
                procArgs.add(subModel);
            }

            var resultHolder = new SubAgentResult[] {null};
            var toolCount = new AtomicInteger(0);

            ProcessRunner.Result procResult = ProcessRunner.command(procArgs).workDir(Path.of(workDir))
                                            .timeout(5, TimeUnit.MINUTES)
                                            .feedStdin(Util.MAPPER.writeValueAsString(taskJson))
                                            .consumeStdoutLines(line -> handleSubAgentEvent(line, sub.name(), logWriter,
                                                                            logFile.toString(), resultHolder,
                                                                            toolCount))
                                            .captureStderr(true).run();

            logWriter.write("\n─── done ───\n");
            logWriter.close();

            String stderrDetail = procResult.stderr() != null && !procResult.stderr().isBlank()
                                            ? "; stderr: " + procResult.stderr().strip()
                                            : "";

            var result = resultHolder[0];
            if (result != null) {
                if (result.error != null && !result.error.isBlank() && !stderrDetail.isBlank()
                                                && !result.error.contains("stderr:")) {
                    result = new SubAgentResult(result.agent, result.output, result.exitCode, result.turns,
                                                    result.toolCount, result.logFile, result.error + stderrDetail);
                }
                saveArtifacts(logFile, sub.name(), task, result);
                return result;
            }

            if (procResult.timedOut()) {
                finish(sub.name(), false, 0);
                return new SubAgentResult(sub.name(), "", 1, 0, toolCount.get(), logFile.toString(),
                                                "timed out after 5 minutes" + stderrDetail);
            }
            if (procResult.exitCode() != 0) {
                finish(sub.name(), false, 0);
                return new SubAgentResult(sub.name(), "", 1, 0, toolCount.get(), logFile.toString(),
                                                "exit code " + procResult.exitCode() + stderrDetail);
            }
            finish(sub.name(), false, 0);
            return new SubAgentResult(sub.name(), "", 1, 0, toolCount.get(), logFile.toString(),
                                            "no done event received" + stderrDetail);

        } catch (Exception e) {
            finish(sub.name(), false, 0);
            return new SubAgentResult(sub.name(), "", 1, 0, 0, null,
                                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // package-private: the NDJSON event parsing is unit-tested directly (the surrounding spawn is integration-only)
    void handleSubAgentEvent(String line, String agentName, BufferedWriter logWriter, String logFilePath,
                                    SubAgentResult[] resultHolder, AtomicInteger toolCount) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = Util.MAPPER.readValue(line, Map.class);
            String type = (String) event.get("type");
            if (type == null)
                return;
            switch (type) {
                case "log" -> {
                    String path = (String) event.get("path");
                    if (path != null)
                        fireActivity(agentName, "log: " + path);
                }
                case "tool_start" -> {
                    String tool = (String) event.get("tool");
                    String args = (String) event.get("args");
                    toolCount.incrementAndGet();
                    String tc = formatToolCall(tool, args);
                    logWriter.write(tc + "\n");
                    logWriter.flush();
                    fireActivity(agentName, tc);
                }
                case "stream" -> {
                    String delta = (String) event.get("delta");
                    if (delta != null) {
                        logWriter.write(delta);
                        logWriter.flush();
                        int lines = Util.countNewlines(delta);
                        if (lines > 0)
                            fireActivity(agentName, "+" + lines);
                    }
                }
                case "done" -> {
                    String output = (String) event.getOrDefault("output", "");
                    int exitCode = event.get("exitCode") instanceof Number n ? n.intValue() : 0;
                    int turns = event.get("turns") instanceof Number n ? n.intValue() : 0;
                    int tc = event.get("toolCount") instanceof Number n ? n.intValue() : 0;
                    long tokens = event.get("tokens") instanceof Number n ? n.longValue() : 0;
                    String error = (String) event.get("error");
                    resultHolder[0] = new SubAgentResult(agentName, output, exitCode, turns, tc, logFilePath, error);
                    finish(agentName, exitCode == 0 && error == null, tokens);
                }
            }
        } catch (Exception e) {
            if (resultHolder[0] == null) {
                resultHolder[0] = new SubAgentResult(agentName, "", 1, 0, toolCount.get(), logFilePath,
                                                "Result lost: failed to parse sub-agent event: " + e.getMessage());
            }
        }
    }

    // --- helpers ---

    static String formatToolCall(String toolName, String argsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = Util.MAPPER.readValue(argsJson, Map.class);
            return "→ " + switch (toolName) {
                case "read" -> "Read " + file(a);
                case "write" -> "Write " + file(a);
                case "edit" -> "Edit " + file(a);
                case "bash" -> "$ " + Util.ellipsize((String) a.get("command"), 60);
                case "grep" -> "Grep /" + a.get("pattern") + "/ in " + file(a);
                case "glob" -> "Glob " + a.get("pattern") + " in " + file(a);
                case "ls" -> "Ls " + file(a);
                default -> toolName;
            };
        } catch (Exception ignored) {
            return toolName;
        }
    }

    static String file(Map<String, Object> a) {
        String p = (String) a.get("path");
        if (p == null)
            p = (String) a.get("filePath");
        if (p == null)
            p = (String) a.get("file_path");
        if (p == null)
            return ".";
        int idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return idx < 0 ? p : p.substring(idx + 1);
    }

    private void fireActivity(String agent, String line) {
        if (activityHandler != null)
            activityHandler.accept(agent, line);
    }

    private void finish(String agent, boolean success, long tokens) {
        if (finishedHandler != null)
            finishedHandler.finished(agent, success, tokens);
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        String text = result.content();
        boolean isSingle = !args.has("tasks") && !args.has("chain");
        String summary;
        var body = new ArrayList<String>();

        if (isSingle) {
            summary = (String) args.getOrDefault("description", args.getOrDefault("instructions", ""));
            boolean fromDescription = summary != null && !summary.isBlank();
            if (fromDescription)
                summary = Util.truncateWords(summary, 50);
            else
                summary = text == null || text.isBlank() ? "" : Util.truncateWords(text, 50);
            if (text != null && !text.isBlank()) {
                var stream = text.lines();
                if (!fromDescription)
                    stream = stream.skip(1);
                stream.limit(OUTPUT_PREVIEW_LINES).filter(line -> !line.isBlank())
                                                .forEach(line -> body.add("│ " + line));
            }
        } else {
            summary = Util.firstLine(text);
            if (text != null && !text.isBlank()) {
                text.lines().skip(1).limit(OUTPUT_PREVIEW_LINES).filter(line -> !line.isBlank())
                                                .forEach(line -> body.add("│ " + line));
            }
        }

        Object logs = result.details().get("logFiles");
        if (logs instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> log))
                    continue;
                Object path = log.get("path");
                if (!(path instanceof String p) || p.isBlank())
                    continue;
                Object agent = log.get("agent");
                String label = agent instanceof String a && !a.isBlank() && list.size() > 1 ? " (" + a + ")" : "";
                body.add("╰→ full output" + label + ": " + Util.shortenCwd(p));
            }
        }
        return ToolResultView.of(summary, body);
    }

    static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Directory holding sub-agent logs. Override with {@code -Dpijota.log.dir=…} (tests isolate it this way). */
    static Path logDir() {
        String override = System.getProperty("pijota.log.dir");
        Path dir = override != null && !override.isBlank()
                                        ? Path.of(override)
                                        : Path.of(System.getProperty("java.io.tmpdir"), "pijota-logs");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
        return dir;
    }

    private Path createLogFile(String agentName) throws IOException {
        String ts = LocalDateTime.now().toString().replace(":", "").substring(0, 15);
        int seq = logCounter.getAndIncrement();
        return logDir().resolve("sub-" + sanitize(agentName) + "-" + ts + "-" + seq + ".log");
    }

    private static void saveArtifacts(Path logFile, String agent, String task, SubAgentResult result) {
        try {
            String base = logFile.getFileName().toString().replace(".log", "");
            Path dir = logFile.getParent();
            Files.writeString(dir.resolve(base + "_input.md"), task);
            Files.writeString(dir.resolve(base + "_output.md"), result.output);
        } catch (IOException ignored) {}
    }

    // --- worktree isolation ---

    private boolean resolveWorktree(Map<String, Object> args) {
        if (Boolean.TRUE.equals(args.get("noWorktree")))
            return false;
        if (Boolean.TRUE.equals(args.get("worktree")))
            return true;
        // default: use worktrees when tasks > 1 and cwd is a git repo
        return args.containsKey("tasks") && isGitRepo(cwd.toString());
    }

    private boolean isGitRepo(String dir) {
        return Files.isDirectory(Path.of(dir, ".git"));
    }

    private Path createWorktree(String agentName, int idx) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"), "pijota-worktrees");
            Files.createDirectories(tmp);
            Path wt = Files.createTempDirectory(tmp, "wt-" + idx + "-" + sanitize(agentName) + "-");
            var result = ProcessRunner.command("git", "worktree", "add", "--detach", wt.toString()).workDir(cwd)
                                            .discardStderr().run();
            if (result.success())
                return wt;
            return wt;
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteWorktree(Path wt) {
        try {
            ProcessRunner.command("git", "worktree", "remove", "--force", wt.toString()).workDir(cwd).discardStderr()
                                            .timeout(10, TimeUnit.SECONDS).run();
        } catch (Exception ignored) {}
        try {
            deleteRecursive(wt);
        } catch (IOException ignored) {}
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (!Files.isDirectory(dir))
            return;
        try (var files = Files.list(dir)) {
            for (var f : files.toList()) {
                if (Files.isDirectory(f))
                    deleteRecursive(f);
                else
                    Files.delete(f);
            }
        }
        Files.delete(dir);
    }

    // --- chain config loading ---

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> loadChainConfigs() {
        var configs = new LinkedHashMap<String, List<Map<String, Object>>>();
        // User chains
        loadChainDir(Path.of(System.getProperty("user.home"), ".pi", "chains"), configs);
        // Project chains
        loadChainDir(cwd.resolve(".pi/chains"), configs);
        return configs;
    }

    private List<Map<String, Object>> resolveChain(List<Map<String, Object>> chain) {
        // If first element is a string, treat as chain preset name
        if (!chain.isEmpty() && chain.getFirst().containsKey("agent"))
            return chain; // already expanded
        // Check if first element contains a name key pointing to a chain config
        String name = (String) chain.getFirst().getOrDefault("name", "");
        if (name.isBlank())
            return chain; // no name, use as-is
        return chainConfigs.get(name);
    }

    private void loadChainDir(Path dir, Map<String, List<Map<String, Object>>> configs) {
        try {
            if (!Files.isDirectory(dir))
                return;
            try (var stream = Files.walk(dir, 2)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".chain.json"))
                                                .forEach(p -> loadChainJson(p, configs));
            }
        } catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void loadChainJson(Path file, Map<String, List<Map<String, Object>>> configs) {
        try {
            Map<String, Object> cfg = Util.MAPPER.readValue(file.toFile(), Map.class);
            String name = (String) cfg.getOrDefault("name", file.getFileName().toString().replace(".chain.json", ""));
            List<Map<String, Object>> chain = (List<Map<String, Object>>) cfg.get("chain");
            if (chain != null && !chain.isEmpty())
                configs.put(name, chain);
        } catch (IOException ignored) {}
    }

    // --- result types ---

    record SubAgentResult(String agent, String output, int exitCode, int turns, int toolCount, String logFile,
                                    String error) {
        String formattedOutput() {
            if (error != null)
                return "Error: " + error;
            return output != null ? output : "";
        }
    }

    static Map<String, Object> detailsForResults(String mode, List<SubAgentResult> results) {
        var out = new LinkedHashMap<String, Object>();
        out.put("mode", mode);
        out.put("resultCount", results.size());
        out.put("succeeded", results.stream().filter(r -> r.exitCode == 0).count());
        var logs = new ArrayList<Map<String, Object>>();
        for (var r : results) {
            if (r.logFile != null && !r.logFile.isBlank()) {
                logs.add(Map.of("agent", r.agent, "path", r.logFile));
            }
        }
        if (!logs.isEmpty())
            out.put("logFiles", logs);
        return out;
    }
}
