package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;
import ai.pijota.util.Emojis;
import ai.pijota.util.Util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class GoalExtension implements Extension {

    private static final String CUSTOM_TYPE = "goal";
    private static final long MAX_OBJECTIVE_LENGTH = 8000;

    private enum Status { ACTIVE, PAUSED, COMPLETE, IDLE, BUDGET_LIMITED }

    record GoalEntry(
        String objective, String status, long tokenBudget,
        long inputTokensAtStart, long outputTokensAtStart, long startedAtMs
    ) {
        String toJson() {
            return Util.MAPPER.createObjectNode()
                .put("objective", objective != null ? objective : "")
                .put("status", status)
                .put("token_budget", tokenBudget)
                .put("input_tokens_at_start", inputTokensAtStart)
                .put("output_tokens_at_start", outputTokensAtStart)
                .put("started_at_ms", startedAtMs)
                .toString();
        }
    }

    private volatile Status status = Status.IDLE;
    private volatile String currentObjective;
    private volatile long tokenBudget;
    private volatile long startedAtMs;
    private volatile long pausedMs;
    private volatile long inputTokensOnStart;
    private volatile long outputTokensOnStart;
    private ExtensionAPI api;

    @Override public String name() { return "goal"; }
    @Override public String description() { return "Codex-style goal tracking and continuation"; }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;

        api.registerSlashCommand(new SlashCommand("goal",
            "View or manage the current goal",
            List.of(), this::handleGoalCommand));

        api.registerTool(new Tool.ToolDefinition(
            "create_goal",
            "Create a goal with an objective and optional token budget. Fails if a non-complete goal exists unless replace_existing is true.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "objective", Map.of("type", "string", "description", "What the agent should accomplish"),
                    "token_budget", Map.of("type", "integer", "description", "Maximum tokens to use before marking budget-limited"),
                    "replace_existing", Map.of("type", "boolean", "description", "Replace current goal even if not complete")
                ),
                "required", List.of("objective")
            ),
            (id, args, cwd) -> CompletableFuture.completedFuture(doCreateGoal(args))
        ));

        api.registerTool(new Tool.ToolDefinition(
            "get_goal",
            "Returns the current goal state including objective, status, token budget, and usage.",
            Map.of("type", "object", "properties", Map.of()),
            (id, args, cwd) -> CompletableFuture.completedFuture(doGetGoal())
        ));

        api.registerTool(new Tool.ToolDefinition(
            "update_goal",
            "Update the goal status. Only supports status: \"complete\".",
            Map.of("type", "object", "properties", Map.of(
                "status", Map.of("type", "string", "description", "Set to \"complete\" to mark the goal as done")
            ), "required", List.of("status")),
            (id, args, cwd) -> CompletableFuture.completedFuture(doUpdateGoal(args))
        ));

        api.registerStatusLines(this::statusLine);

        api.onTurnStart((ev, ctx) -> { if (status == Status.ACTIVE) pausedMs = 0; });

        api.onTurnEnd((ev, ctx) -> {
            if (status != Status.ACTIVE) return;
            if (tokenBudget > 0 && currentUsage() >= tokenBudget) {
                status = Status.BUDGET_LIMITED;
                saveEntry();
                return;
            }
            api.sendUserMessage("Continue working on the goal: " + currentObjective, "steer");
        });

        api.onSessionReady((ev, ctx) -> loadFromSession());
    }

    private void loadFromSession() {
        for (int i = api.runtime().messages().size() - 1; i >= 0; i--) {
            Message msg = api.runtime().messages().get(i);
            if (msg instanceof Message.Custom c && CUSTOM_TYPE.equals(c.customType())) {
                String json = c.content();
                if (json == null || json.isBlank()) break;
                try {
                    var node = Util.MAPPER.readTree(json);
                    String storedStatus = node.has("status") ? node.get("status").asText() : "";
                    if ("idle".equals(storedStatus) || "complete".equals(storedStatus)) break;
                    currentObjective = node.has("objective") ? node.get("objective").asText() : "";
                    status = parseStatus(storedStatus);
                    tokenBudget = node.has("token_budget") ? node.get("token_budget").asLong() : 0;
                    inputTokensOnStart = node.has("input_tokens_at_start") ? node.get("input_tokens_at_start").asLong() : 0;
                    outputTokensOnStart = node.has("output_tokens_at_start") ? node.get("output_tokens_at_start").asLong() : 0;
                    startedAtMs = node.has("started_at_ms") ? node.get("started_at_ms").asLong() : 0;
                } catch (Exception ignored) {}
                break;
            }
        }
    }

    // ---- slash command ----

    private void handleGoalCommand(String args) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) { showGoal(); return; }
        switch (trimmed) {
            case "pause" -> doPause();
            case "resume" -> doResume();
            case "clear" -> doClear();
            default -> startOrReplace(trimmed);
        }
    }

    private void showGoal() {
        if (status == Status.IDLE) { api.runtime().setStatus("No active goal. Use /goal <objective> to create one."); return; }
        long elapsed = elapsedMs();
        long used = currentUsage();
        api.runtime().setStatus(String.format("%s %s · %s · %s · %s",
            Emojis.DART, currentObjective, statusLabel(), Util.fmtCount(used), Util.formatDuration(elapsed)));
    }

    private void startOrReplace(String objective) {
        if (objective.length() > MAX_OBJECTIVE_LENGTH) { api.runtime().setStatus("Objective too long (max " + MAX_OBJECTIVE_LENGTH + " chars)"); return; }
        if (status == Status.ACTIVE || status == Status.PAUSED || status == Status.BUDGET_LIMITED) { api.runtime().setStatus("Goal already active. Use /goal clear first or /goal pause."); return; }
        startGoal(objective, 0);
        saveEntry();
    }

    private void doPause() {
        if (status != Status.ACTIVE) { api.runtime().setStatus("No active goal to pause"); return; }
        status = Status.PAUSED;
        pausedMs = System.currentTimeMillis();
        saveEntry();
        api.runtime().setStatus(Emojis.PAUSE + " Goal paused: " + currentObjective);
    }

    private void doResume() {
        if (status != Status.PAUSED && status != Status.BUDGET_LIMITED) { api.runtime().setStatus("No paused goal to resume"); return; }
        if (status == Status.PAUSED && pausedMs > 0) startedAtMs += System.currentTimeMillis() - pausedMs;
        status = Status.ACTIVE;
        pausedMs = 0;
        saveEntry();
        api.runtime().setStatus(Emojis.PLAY + " Goal resumed: " + currentObjective);
    }

    private void doClear() {
        if (status == Status.IDLE) { api.runtime().setStatus("No goal to clear"); return; }
        status = Status.IDLE;
        currentObjective = null;
        tokenBudget = 0;
        startedAtMs = 0;
        pausedMs = 0;
        saveEntry();
        api.runtime().setStatus("Goal cleared");
    }

    // ---- model tools ----

    private Tool.ToolResult doCreateGoal(Map<String, Object> args) {
        String objective = (String) args.get("objective");
        if (objective == null || objective.isBlank()) return Tool.ToolResult.error("objective is required");
        if (objective.length() > MAX_OBJECTIVE_LENGTH) return Tool.ToolResult.error("objective exceeds " + MAX_OBJECTIVE_LENGTH + " chars");
        boolean replace = Boolean.TRUE.equals(args.get("replace_existing"));
        if (!replace && (status == Status.ACTIVE || status == Status.PAUSED || status == Status.BUDGET_LIMITED))
            return Tool.ToolResult.error("Goal already exists. Use replace_existing: true to override.");
        Number budget = (Number) args.get("token_budget");
        startGoal(objective, budget != null ? Math.max(0, budget.longValue()) : 0);
        saveEntry();
        return Tool.ToolResult.success(makeEntry().toJson());
    }

    private Tool.ToolResult doGetGoal() {
        if (status == Status.IDLE) return Tool.ToolResult.success("{\"status\": \"idle\"}");
        return Tool.ToolResult.success(makeEntry().toJson());
    }

    private Tool.ToolResult doUpdateGoal(Map<String, Object> args) {
        String newStatus = (String) args.get("status");
        if (!"complete".equals(newStatus)) return Tool.ToolResult.error("Only status \"complete\" is supported");
        if (status == Status.IDLE) return Tool.ToolResult.error("No active goal to update");
        if (status == Status.COMPLETE) return Tool.ToolResult.success("Goal already complete");
        status = Status.COMPLETE;
        saveEntry();
        return Tool.ToolResult.success(String.format("Goal completed: %s\n  used %s\n  elapsed %s",
            currentObjective, Util.fmtCount(currentUsage()), Util.formatDuration(elapsedMs())));
    }

    // ---- internals ----

    private void startGoal(String objective, long budget) {
        status = Status.ACTIVE;
        currentObjective = objective;
        tokenBudget = budget;
        startedAtMs = System.currentTimeMillis();
        pausedMs = 0;
        var llm = api.runtime().llm();
        inputTokensOnStart = llm.totalInputTokens();
        outputTokensOnStart = llm.totalOutputTokens();
    }

    private GoalEntry makeEntry() {
        return new GoalEntry(currentObjective, status.name().toLowerCase(), tokenBudget,
            inputTokensOnStart, outputTokensOnStart, startedAtMs);
    }

    private void saveEntry() {
        String json = status == Status.IDLE ? "{\"status\":\"idle\"}" : makeEntry().toJson();
        api.sendMessage(new Message.Custom(CUSTOM_TYPE, json));
    }

    private List<String> statusLine() {
        if (status != Status.ACTIVE && status != Status.BUDGET_LIMITED) return List.of();
        long elapsed = elapsedMs();
        long used = currentUsage();
        String line = String.format("%s %s · %s%s",
            Emojis.DART, Util.truncate(currentObjective, 60), Util.formatDuration(elapsed),
            tokenBudget > 0 ? " · " + Util.fmtCount(used) + "/" + Util.fmtCount(tokenBudget) : "");
        return List.of(line);
    }

    private long elapsedMs() {
        if (startedAtMs == 0) return 0;
        long endMs = (status == Status.PAUSED && pausedMs > 0) ? pausedMs : System.currentTimeMillis();
        return Math.max(0, endMs - startedAtMs);
    }

    private long currentUsage() {
        var llm = api.runtime().llm();
        long in = llm.totalInputTokens() - inputTokensOnStart;
        long out = llm.totalOutputTokens() - outputTokensOnStart;
        return Math.max(0, in + out);
    }

    private String statusLabel() {
        return switch (status) {
            case ACTIVE -> "active";
            case PAUSED -> "paused";
            case COMPLETE -> "complete";
            case BUDGET_LIMITED -> "budget-limited";
            case IDLE -> "idle";
        };
    }

    private static Status parseStatus(String s) {
        return switch (s) {
            case "active" -> Status.ACTIVE;
            case "paused" -> Status.PAUSED;
            case "complete" -> Status.COMPLETE;
            case "budget-limited", "budget_limited" -> Status.BUDGET_LIMITED;
            default -> Status.IDLE;
        };
    }
}
