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
    private static final int DEFAULT_MAX_ITERATIONS = 25;

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

        api.registerSlashCommand(new SlashCommand("loop",
                                        "Start (/loop <objective>) or stop (/loop stop) an autonomous loop", List.of(),
                                        this::handleLoopCommand));

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
                                        .intParam("max_iterations", "Max auto-continue iterations before stopping (0 = unlimited)",
                                                                        false)
                                        .intParam("max_seconds", "Max wall-clock seconds before stopping (0 = unlimited)",
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

        api.on(ExtensionEvent.TurnStart.class, _ -> enforceHardLimits());

        api.on(ExtensionEvent.TurnEnd.class, _ -> {
            if (enforceHardLimits())
                return;
            GoalState current = state.get();
            if (current.continueOnIdle()) {
                if (current.iterationCapReached()) {
                    updateState(s -> s.markIterationLimited(System.currentTimeMillis()));
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastContinueMs > 1000) {
                    lastContinueMs = now;
                    updateState(GoalState::incrementIteration);
                    // FOLLOW_UP (not STEER) re-prompts after a turn that ended with no tool calls; gate on goal state,
                    // not isIdle() (still true at TurnEnd).
                    api.sendUserMessage(continuationPrompt(current), DeliverAs.FOLLOW_UP);
                }
            }
        });

    }

    /** Apply the token-budget + time hard caps; returns true if the goal isn't active or was just stopped by a cap. */
    private boolean enforceHardLimits() {
        GoalState current = state.get();
        if (!current.isActive())
            return true;
        long now = System.currentTimeMillis();
        var llm = api.runtime().llm();
        if (current.hasBudget() && current.wouldExceedBudget(llm.totalInputTokens(), llm.totalOutputTokens())) {
            updateState(s -> s.markBudgetLimited(now));
            return true;
        }
        if (current.timeExceeded(now)) {
            updateState(s -> s.markTimeLimited(now));
            return true;
        }
        return false;
    }

    /**
     * The re-prompt sent on each idle continuation; folds in the stop-when criterion so the model can self-complete.
     */
    private static String continuationPrompt(GoalState g) {
        String base = "Continue toward the objective: " + g.objective();
        if (!g.completionCriterion().isEmpty())
            base += ". When this is satisfied — " + g.completionCriterion()
                                            + " — call update_goal with status complete and stop.";
        return base;
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
        appendIterations(sb, current);
        appendTime(sb, current, System.currentTimeMillis());
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

    // ---- /loop ----

    private void handleLoopCommand(String args) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            showGoal();
            return;
        }
        if (trimmed.equalsIgnoreCase("stop")) {
            doStopLoop();
            return;
        }
        startLoop(trimmed);
    }

    /** Default iteration cap, overridable via the {@code loop-max-iterations} flag (CLI / settings.json extensions). */
    private int defaultMaxIterations() {
        return api.runtime().getFlag("loop-max-iterations").map(v -> {
            try {
                return Math.max(0, Integer.parseInt(v.strip()));
            } catch (NumberFormatException e) {
                return DEFAULT_MAX_ITERATIONS;
            }
        }).orElse(DEFAULT_MAX_ITERATIONS);
    }

    private void startLoop(String input) {
        int maxIterations = defaultMaxIterations();
        long tokenBudget = defaultMaxTokens();
        long maxTimeMs = defaultMaxTimeMs();
        String criterion = "";
        boolean continueOnIdle = true;
        // Strip the inline overrides out of the objective text; everything else is the objective.
        var words = new ArrayList<String>();
        String[] parts = input.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.equals("--no-continue")) {
                continueOnIdle = false;
            } else if (p.equals("--max-iterations") && i + 1 < parts.length) {
                maxIterations = parseCap(parts[++i], maxIterations);
            } else if (p.startsWith("--max-iterations=")) {
                maxIterations = parseCap(p.substring("--max-iterations=".length()), maxIterations);
            } else if (p.equals("--max-tokens") && i + 1 < parts.length) {
                tokenBudget = parseLongCap(parts[++i], tokenBudget);
            } else if (p.startsWith("--max-tokens=")) {
                tokenBudget = parseLongCap(p.substring("--max-tokens=".length()), tokenBudget);
            } else if (p.equals("--max-time") && i + 1 < parts.length) {
                maxTimeMs = parseDuration(parts[++i], maxTimeMs);
            } else if (p.startsWith("--max-time=")) {
                maxTimeMs = parseDuration(p.substring("--max-time=".length()), maxTimeMs);
            } else if (p.startsWith("--stop-when=")) {
                criterion = stripQuotes(p.substring("--stop-when=".length()));
            } else if (p.equals("--stop-when")) {
                // The condition is free text — consume the rest of the line (so put --stop-when last).
                var rest = new ArrayList<String>();
                while (i + 1 < parts.length)
                    rest.add(parts[++i]);
                criterion = stripQuotes(String.join(" ", rest));
            } else if (p.startsWith("--")) {
                api.runtime().setStatus("Unknown /loop flag: " + p + " — " + LOOP_USAGE);
                return;
            } else {
                words.add(p);
            }
        }
        String objective = String.join(" ", words).strip();
        if (objective.isEmpty()) {
            api.runtime().setStatus(LOOP_USAGE);
            return;
        }
        if (objective.length() > MAX_OBJECTIVE_LENGTH) {
            api.runtime().setStatus("Objective too long (max " + MAX_OBJECTIVE_LENGTH + " chars)");
            return;
        }
        GoalState current = state.get();
        if (!current.isTerminal()) {
            api.runtime().setStatus("A goal/loop is already active. Use /loop stop or /goal clear first.");
            return;
        }
        long now = System.currentTimeMillis();
        var llm = api.runtime().llm();
        int cap = maxIterations;
        long budget = tokenBudget;
        long timeMs = maxTimeMs;
        String crit = criterion;
        boolean cont = continueOnIdle;
        updateState(s -> GoalState.start(objective, budget, crit, cont, cap, timeMs, llm.totalInputTokens(),
                                        llm.totalOutputTokens(), now));
        api.runtime().setStatus(Emojis.DART + " Loop: " + objective + loopCapsSummary(cap, budget, timeMs));
    }

    private static final String LOOP_USAGE = "Usage: /loop <objective> [--max-iterations N] [--max-tokens N] [--max-time 30m] [--stop-when \"…\"] [--no-continue]";

    private static String loopCapsSummary(int cap, long tokenBudget, long maxTimeMs) {
        var caps = new ArrayList<String>();
        if (cap > 0)
            caps.add("≤" + cap + " iter");
        if (tokenBudget > 0)
            caps.add("≤" + Util.fmtCount(tokenBudget) + " tok");
        if (maxTimeMs > 0)
            caps.add("≤" + Util.formatDuration(maxTimeMs));
        return caps.isEmpty() ? "" : "  (" + String.join(", ", caps) + ")";
    }

    private static int parseCap(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongCap(String value, long fallback) {
        try {
            return Math.max(0, Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Parse a duration like {@code 90s}, {@code 30m}, {@code 2h}, {@code 1h30m}; a bare number is seconds. ms result.
     */
    static long parseDuration(String value, long fallback) {
        if (value == null)
            return fallback;
        String v = value.strip().toLowerCase();
        if (v.isEmpty())
            return fallback;
        if (v.matches("\\d+")) {
            try {
                return Long.parseLong(v) * 1000L;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        long ms = 0;
        long num = 0;
        boolean pendingDigits = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) {
                num = num * 10 + (c - '0');
                pendingDigits = true;
            } else {
                long unit = switch (c) {
                    case 's' -> 1000L;
                    case 'm' -> 60_000L;
                    case 'h' -> 3_600_000L;
                    default -> -1L;
                };
                if (unit < 0 || !pendingDigits)
                    return fallback;
                ms += num * unit;
                num = 0;
                pendingDigits = false;
            }
        }
        if (pendingDigits) // trailing digits without a unit → malformed
            return fallback;
        return ms > 0 ? ms : fallback;
    }

    private static String stripQuotes(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && t.charAt(t.length() - 1) == t.charAt(0))
            return t.substring(1, t.length() - 1).strip();
        return t;
    }

    private long defaultMaxTokens() {
        return api.runtime().getFlag("loop-max-tokens").map(v -> parseLongCap(v, 0L)).orElse(0L);
    }

    private long defaultMaxTimeMs() {
        return api.runtime().getFlag("loop-max-time").map(v -> parseDuration(v, 0L)).orElse(0L);
    }

    private void doStopLoop() {
        GoalState current = state.get();
        if (current.status() == GoalState.Status.IDLE) {
            api.runtime().setStatus("No active loop to stop");
            return;
        }
        // Halt auto-continuation portably: clearing → idle, so the TurnEnd hook won't re-launch. The in-flight turn (if
        // any) is stopped via the frontend's own interrupt (Esc / /stop / /interrupt).
        updateState(GoalState::clear);
        api.runtime().setStatus(Emojis.PAUSE + " Loop stopped");
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
        Number maxIter = (Number) args.get("max_iterations");
        int maxIterations = maxIter != null ? Math.max(0, maxIter.intValue()) : 0;
        Number maxSec = (Number) args.get("max_seconds");
        long maxTimeMs = maxSec != null ? Math.max(0, maxSec.longValue()) * 1000L : 0;
        long now = System.currentTimeMillis();
        var llm = api.runtime().llm();
        GoalState next = GoalState.start(objective, tokenBudget, criterion, continueOnIdle, maxIterations, maxTimeMs,
                                        llm.totalInputTokens(), llm.totalOutputTokens(), now);
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
        appendIterations(sb, current);
        appendTime(sb, current, System.currentTimeMillis());
        return List.of(sb.toString());
    }

    private long currentUsage(GoalState goalState) {
        var llm = api.runtime().llm();
        return goalState.currentUsage(llm.totalInputTokens(), llm.totalOutputTokens());
    }

    private static void appendIterations(StringBuilder sb, GoalState g) {
        if (g.maxIterations() > 0 || g.iterations() > 0)
            sb.append(" · iter ").append(g.iterations()).append(g.maxIterations() > 0 ? "/" + g.maxIterations() : "");
    }

    private static void appendTime(StringBuilder sb, GoalState g, long now) {
        if (g.hasTimeLimit())
            sb.append(" · ").append(Util.formatDuration(g.remainingMs(now))).append(" left");
    }

    static String statusLabel(GoalState.Status status) {
        return switch (status) {
            case ACTIVE -> "active";
            case PAUSED -> "paused";
            case COMPLETE -> "complete";
            case BUDGET_LIMITED -> "budget-limited";
            case ITERATION_LIMITED -> "iteration-limited";
            case TIME_LIMITED -> "time-limited";
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
        if (goalState.maxIterations() > 0)
            lines.add("iterations: " + goalState.iterations() + "/" + goalState.maxIterations());
        if (goalState.hasTimeLimit())
            lines.add("time left: " + Util.formatDuration(goalState.remainingMs(System.currentTimeMillis())));
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
