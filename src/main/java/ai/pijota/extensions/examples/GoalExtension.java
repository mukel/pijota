package ai.pijota.extensions.examples;

import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.DeliverAs;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;
import ai.pijota.util.Emojis;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Codex-style goal tracking and auto-continuation. When the model is rate-limited or idled mid-task, the goal is
 * captured and the agent is automatically restarted with context to complete the interrupted work.
 */
public final class GoalExtension implements Extension {

    private static final String CUSTOM_TYPE = "goal";
    private static final long MAX_OBJECTIVE_LENGTH = 8000;

    private final AtomicReference<GoalState> state = new AtomicReference<>(GoalState.idle());
    private volatile long lastContinueMs;
    private ExtensionAPI api;

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return "Codex-style goal tracking and continuation";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;

        api.registerSlashCommand(new SlashCommand("goal", "View or manage the current goal", List.of(),
                                        this::handleGoalCommand));

        api.registerTool(Tool.named("create_goal").description(
                                        "Create a goal with an objective and optional token budget. Fails if a non-complete goal exists unless replace_existing is true.")
                                        .stringParam("objective", "What the agent should accomplish", true)
                                        .intParam("token_budget", "Maximum tokens to use before marking budget-limited",
                                                                        false)
                                        .stringParam("completion_criterion",
                                                                        "Optional condition that determines when the goal is satisfied",
                                                                        false)
                                        .boolParam("continue_on_idle", "Whether the agent should continue working when idle",
                                                                        false)
                                        .boolParam("replace_existing", "Replace current goal even if not complete",
                                                                        false)
                                        .resultRenderer(createGoalRenderer())
                                        .run((args, cwd) -> doCreateGoal(args.map())));

        api.registerTool(Tool.named("get_goal").description(
                                        "Returns the current goal state including objective, status, budget, progress, and usage.")
                                        .run((args, cwd) -> doGetGoal()));

        api.registerTool(Tool.named("update_goal").description(
                                        "Update the goal status. Supports status: complete, pause, resume, idle.")
                                        .enumParam("status", "Set to complete, pause, resume, or idle", true,
                                                                        List.of("complete", "pause", "resume", "idle"))
                                        .resultRenderer(updateGoalRenderer())
                                        .run((args, cwd) -> doUpdateGoal(args.map())));

        api.registerTool(Tool.named("add_goal_progress")
                                        .description("Append a progress note or milestone to the current goal.")
                                        .stringParam("note", "Progress note or milestone", true)
                                        .resultRenderer(addProgressRenderer())
                                        .run((args, cwd) -> doAddProgress(args.map())));

        api.registerStatusLines(this::statusLine);

        api.on(ExtensionEvent.TurnStart.class, _ -> {
            GoalState current = state.get();
            if (current.isActive() && current.hasBudget()) {
                var llm = api.runtime().llm();
                if (current.wouldExceedBudget(llm.totalInputTokens(), llm.totalOutputTokens())) {
                    updateState(s -> s.markBudgetLimited(System.currentTimeMillis()));
                }
            }
        });

        api.on(ExtensionEvent.TurnEnd.class, _ -> {
            GoalState current = state.get();
            if (!current.isActive())
                return;
            var llm = api.runtime().llm();
            if (current.hasBudget() && current.wouldExceedBudget(llm.totalInputTokens(), llm.totalOutputTokens())) {
                updateState(s -> s.markBudgetLimited(System.currentTimeMillis()));
                return;
            }
            if (current.continueOnIdle() && api.runtime().isIdle()) {
                long now = System.currentTimeMillis();
                if (now - lastContinueMs > 1000) {
                    lastContinueMs = now;
                    api.sendUserMessage("Continue working on the goal: " + current.objective(), DeliverAs.STEER);
                }
            }
        });

    }

    @Override
    public void onReady(ExtensionRuntime runtime) {
        loadFromSession();
    }

    private void loadFromSession() {
        GoalState latest = GoalState.idle();
        for (Message msg : api.runtime().messages()) {
            if (msg instanceof Message.Custom c && CUSTOM_TYPE.equals(c.customType())) {
                String json = c.details() != null ? (String) c.details().get("json") : null;
                if (json != null && !json.isBlank())
                    latest = GoalState.fromJson(json);
                else
                    latest = GoalState.fromJson(c.content());
            }
        }
        state.set(latest);
    }

    private void updateState(Function<GoalState, GoalState> transform) {
        GoalState previous;
        GoalState next;
        do {
            previous = state.get();
            next = transform.apply(previous);
        } while (!state.compareAndSet(previous, next));
        if (!previous.equals(next)) {
            saveEntry(next);
        }
    }

    // ---- slash command ----

    private void handleGoalCommand(String args) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            showGoal();
            return;
        }
        switch (trimmed.toLowerCase()) {
            case "pause" -> doPause();
            case "resume" -> doResume();
            case "clear" -> doClear();
            default -> {
                if (trimmed.toLowerCase().startsWith("progress ")) {
                    doAddProgressFromCommand(trimmed.substring(8).strip());
                } else {
                    startOrReplace(trimmed);
                }
            }
        }
    }

    private void showGoal() {
        GoalState current = state.get();
        if (current.status() == GoalState.Status.IDLE) {
            api.runtime().setStatus("No active goal. Use /goal <objective> to create one.");
            return;
        }
        long elapsed = current.elapsedMs(System.currentTimeMillis());
        long used = currentUsage(current);
        StringBuilder sb = new StringBuilder();
        sb.append(Emojis.DART).append(' ').append(current.objective());
        sb.append(" · ").append(statusLabel(current.status()));
        sb.append(" · ").append(Util.formatDuration(elapsed));
        if (current.hasBudget())
            sb.append(" · ").append(Util.fmtCount(used)).append("/").append(Util.fmtCount(current.tokenBudget()));
        if (!current.completionCriterion().isEmpty())
            sb.append(" · criterion: ").append(current.completionCriterion());
        if (!current.progress().isEmpty())
            sb.append(" · progress: ").append(current.progress().size());
        // Surface the management actions — the command advertises "view or manage", so show how.
        sb.append("  ·  manage: ");
        sb.append(current.status() == GoalState.Status.PAUSED
                                        ? "/goal resume · clear"
                                        : "/goal pause · clear · progress <note>");
        api.runtime().setStatus(sb.toString());
    }

    static String[] parseObjective(String objective) {
        String[] parts = objective.split("\\|", 2);
        String obj = parts[0].strip();
        String criterion = parts.length > 1 ? parts[1].strip() : "";
        return new String[] {obj, criterion};
    }

    private void startOrReplace(String objective) {
        if (objective.length() > MAX_OBJECTIVE_LENGTH) {
            api.runtime().setStatus("Objective too long (max " + MAX_OBJECTIVE_LENGTH + " chars)");
            return;
        }
        GoalState current = state.get();
        if (current.status() == GoalState.Status.ACTIVE || current.status() == GoalState.Status.PAUSED
                                        || current.status() == GoalState.Status.BUDGET_LIMITED) {
            api.runtime().setStatus("Goal already active. Use /goal clear first or /goal pause.");
            return;
        }
        String[] parsed = parseObjective(objective);
        String obj = parsed[0];
        String criterion = parsed[1];
        long now = System.currentTimeMillis();
        var llm = api.runtime().llm();
        updateState(s -> GoalState.start(obj, 0, criterion, false, llm.totalInputTokens(), llm.totalOutputTokens(),
                                        now));
        api.runtime().setStatus(Emojis.DART + " Goal: " + obj);
    }

    private void doPause() {
        GoalState current = state.get();
        if (current.status() != GoalState.Status.ACTIVE && current.status() != GoalState.Status.BUDGET_LIMITED) {
            api.runtime().setStatus("No active goal to pause");
            return;
        }
        updateState(s -> s.pause(System.currentTimeMillis()));
        api.runtime().setStatus(Emojis.PAUSE + " Goal paused: " + state.get().objective());
    }

    private void doResume() {
        GoalState current = state.get();
        if (current.status() != GoalState.Status.PAUSED) {
            api.runtime().setStatus("No paused goal to resume");
            return;
        }
        updateState(s -> s.resume(System.currentTimeMillis()));
        api.runtime().setStatus(Emojis.PLAY + " Goal resumed: " + state.get().objective());
    }

    private void doClear() {
        GoalState current = state.get();
        if (current.status() == GoalState.Status.IDLE) {
            api.runtime().setStatus("No goal to clear");
            return;
        }
        updateState(GoalState::clear);
        api.runtime().setStatus("Goal cleared");
    }

    private void doAddProgressFromCommand(String note) {
        if (note.isBlank()) {
            api.runtime().setStatus("Usage: /goal progress <note>");
            return;
        }
        GoalState current = state.get();
        if (current.status() == GoalState.Status.IDLE) {
            api.runtime().setStatus("No active goal");
            return;
        }
        updateState(s -> s.addProgress(note, System.currentTimeMillis()));
        api.runtime().setStatus("Progress added");
    }

    // ---- model tools ----

    private Tool.ToolResult doCreateGoal(Map<String, Object> args) {
        String objective = (String) args.get("objective");
        if (objective == null || objective.isBlank())
            return Tool.ToolResult.error("objective is required");
        if (objective.length() > MAX_OBJECTIVE_LENGTH)
            return Tool.ToolResult.error("objective exceeds " + MAX_OBJECTIVE_LENGTH + " chars");
        boolean replace = Boolean.TRUE.equals(args.get("replace_existing"));
        GoalState current = state.get();
        if (!replace && !current.isTerminal())
            return Tool.ToolResult.error("Goal already exists. Use replace_existing: true to override.");
        Number budget = (Number) args.get("token_budget");
        long tokenBudget = budget != null ? Math.max(0, budget.longValue()) : 0;
        String criterion = (String) args.get("completion_criterion");
        boolean continueOnIdle = Boolean.TRUE.equals(args.get("continue_on_idle"));
        long now = System.currentTimeMillis();
        var llm = api.runtime().llm();
        GoalState next = GoalState.start(objective, tokenBudget, criterion, continueOnIdle, llm.totalInputTokens(),
                                        llm.totalOutputTokens(), now);
        updateState(s -> next);
        return Tool.ToolResult.success(renderGoal(next));
    }

    private Tool.ToolResult doGetGoal() {
        return Tool.ToolResult.success(renderGoal(state.get()));
    }

    private Tool.ToolResult doUpdateGoal(Map<String, Object> args) {
        String newStatus = (String) args.get("status");
        if (newStatus == null)
            return Tool.ToolResult.error("status is required");
        GoalState current = state.get();
        long now = System.currentTimeMillis();
        return switch (newStatus.toLowerCase()) {
            case "complete" -> {
                if (current.status() == GoalState.Status.IDLE)
                    yield Tool.ToolResult.error("No active goal to complete");
                updateState(s -> s.complete(now));
                yield Tool.ToolResult.success(renderGoal(state.get()));
            }
            case "pause" -> {
                if (current.status() != GoalState.Status.ACTIVE && current.status() != GoalState.Status.BUDGET_LIMITED)
                    yield Tool.ToolResult.error("No active goal to pause");
                updateState(s -> s.pause(now));
                yield Tool.ToolResult.success(renderGoal(state.get()));
            }
            case "resume" -> {
                if (current.status() != GoalState.Status.PAUSED)
                    yield Tool.ToolResult.error("No paused goal to resume");
                updateState(s -> s.resume(now));
                yield Tool.ToolResult.success(renderGoal(state.get()));
            }
            case "idle", "clear" -> {
                if (current.status() == GoalState.Status.IDLE)
                    yield Tool.ToolResult.error("No goal to clear");
                updateState(GoalState::clear);
                yield Tool.ToolResult.success("Goal cleared");
            }
            default -> Tool.ToolResult.error("Unsupported status: " + newStatus);
        };
    }

    private Tool.ToolResult doAddProgress(Map<String, Object> args) {
        String note = (String) args.get("note");
        if (note == null || note.isBlank())
            return Tool.ToolResult.error("note is required");
        GoalState current = state.get();
        if (current.status() == GoalState.Status.IDLE)
            return Tool.ToolResult.error("No active goal");
        long now = System.currentTimeMillis();
        updateState(s -> s.addProgress(note, now));
        return Tool.ToolResult.success(renderGoal(state.get()));
    }

    // ---- internals ----

    private void saveEntry(GoalState goalState) {
        boolean idle = goalState.status() == GoalState.Status.IDLE;
        // These entries are state snapshots persisted for reload — shown via the status line, not as repeated
        // transcript
        // lines (Message.Custom.SILENT). Omit json when idle rather than storing null.
        var details = new HashMap<String, Object>();
        if (!idle)
            details.put("json", goalState.toJson());
        details.put(Message.Custom.SILENT, true);
        String summary = idle
                                        ? "no goal"
                                        : statusLabel(goalState.status()) + " · "
                                                                        + Util.truncate(goalState.objective(), 60);
        api.sendMessage(new Message.Custom(CUSTOM_TYPE, summary, details));
    }

    private List<String> statusLine() {
        GoalState current = state.get();
        if (current.status() == GoalState.Status.IDLE)
            return List.of();
        long elapsed = current.elapsedMs(System.currentTimeMillis());
        long used = currentUsage(current);
        StringBuilder sb = new StringBuilder();
        sb.append(Emojis.DART).append(' ').append(Util.truncate(current.objective(), 60));
        sb.append(" · ").append(statusLabel(current.status()));
        sb.append(" · ").append(Util.formatDuration(elapsed));
        if (current.hasBudget())
            sb.append(" · ").append(Util.fmtCount(used)).append("/").append(Util.fmtCount(current.tokenBudget()));
        return List.of(sb.toString());
    }

    private long currentUsage(GoalState goalState) {
        var llm = api.runtime().llm();
        return goalState.currentUsage(llm.totalInputTokens(), llm.totalOutputTokens());
    }

    static String statusLabel(GoalState.Status status) {
        return switch (status) {
            case ACTIVE -> "active";
            case PAUSED -> "paused";
            case COMPLETE -> "complete";
            case BUDGET_LIMITED -> "budget-limited";
            case IDLE -> "idle";
        };
    }

    private static Tool.ResultRenderer createGoalRenderer() {
        return (args, result, cwd) -> {
            if (result.isError())
                return Tool.ToolResultView.of("Failed to create goal");
            String objective = String.valueOf(args.getOrDefault("objective", ""));
            return Tool.ToolResultView.of("Created goal: " + Util.truncate(objective, 60));
        };
    }

    private static Tool.ResultRenderer updateGoalRenderer() {
        return (args, result, cwd) -> {
            if (result.isError())
                return Tool.ToolResultView.of("Goal update failed");
            String status = String.valueOf(args.getOrDefault("status", ""));
            return Tool.ToolResultView.of("Goal status updated to " + status);
        };
    }

    private static Tool.ResultRenderer addProgressRenderer() {
        return (args, result, cwd) -> {
            if (result.isError())
                return Tool.ToolResultView.of("Failed to add progress");
            String note = String.valueOf(args.getOrDefault("note", ""));
            return Tool.ToolResultView.of("Progress: " + Util.truncate(note, 60));
        };
    }

    private String renderGoal(GoalState goalState) {
        if (goalState.status() == GoalState.Status.IDLE)
            return "status: idle";
        long elapsed = goalState.elapsedMs(System.currentTimeMillis());
        long used = currentUsage(goalState);
        var lines = new ArrayList<String>();
        lines.add("objective: " + goalState.objective());
        lines.add("status: " + statusLabel(goalState.status()));
        lines.add("elapsed: " + Util.formatDuration(elapsed));
        if (goalState.hasBudget())
            lines.add("usage: " + Util.fmtCount(used) + "/" + Util.fmtCount(goalState.tokenBudget()));
        if (!goalState.completionCriterion().isEmpty())
            lines.add("completion criterion: " + goalState.completionCriterion());
        if (!goalState.progress().isEmpty()) {
            lines.add("progress:");
            for (String entry : goalState.progress())
                lines.add("  - " + entry);
        }
        return String.join("\n", lines);
    }
}
