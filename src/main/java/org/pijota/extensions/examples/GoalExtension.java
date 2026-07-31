package org.pijota.extensions.examples;

import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.DeliverAs;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.tools.Tool;
import org.pijota.util.Emojis;
import org.pijota.util.ErrorUtil;
import org.pijota.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Codex-style goal tracking and auto-continuation. When the model is rate-limited or idled mid-task, the goal is
 * captured and the agent is automatically restarted with context to complete the interrupted work.
 */
public final class GoalExtension implements Extension {

    private static final String CUSTOM_TYPE = "goal";
    private static final long MAX_OBJECTIVE_LENGTH = 8000;
    private static final int DEFAULT_MAX_ITERATIONS = 25;
    // Long-running resilience: a transient LLM/tool failure shouldn't kill an autonomous loop. Back off and retry the
    // continuation, giving up only after this many consecutive failures.
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long BACKOFF_BASE_MS = 2_000;

    private final AtomicReference<GoalState> state = new AtomicReference<>(GoalState.idle());
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long lastContinueMs;
    private ExtensionAPI api;
    private ScheduledExecutorService scheduler;
    /** Schedules a backed-off continuation retry. Package-private so tests can run it synchronously. */
    BiConsumer<Runnable, Long> backoffScheduler;

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

        // /goal and /loop are one command; /loop just defaults continue-on-idle ON (autonomous).
        api.registerSlashCommand(new SlashCommand("goal",
                                        "Track a goal: /goal <objective> [flags]; pause/resume/stop/progress",
                                        List.of(), args -> handleCommand(args, false)));

        api.registerSlashCommand(new SlashCommand("loop",
                                        "Autonomous goal (auto-continues): /loop <objective> [flags]; /loop stop",
                                        List.of(), args -> handleCommand(args, true)));

        // Model-facing surface is deliberately tiny. Work, and say when done. The rich management verbs
        // (pause/resume/stop/progress, status) stay human-side on /goal + /loop. Caps are forgiving strings the model
        // copies verbatim (no arithmetic: small models drop digits when converting "30m"→1800 or "1M"→1000000).
        api.registerTool(Tool.named("goal_set").description(
                                        "Set the objective the agent should work toward, with optional limits. Replaces any existing goal.")
                                        .stringParam("objective", "What the agent should accomplish", true)
                                        .stringParam("max_time", "Wall-clock limit as a duration string, e.g. \"30m\", \"2h\", \"90s\", \"1h30m\". Pass the user's phrasing directly; do NOT convert to seconds.",
                                                                        false)
                                        .stringParam("max_tokens", "Token budget, e.g. \"500k\", \"1M\", \"2.5M\". Copy the user's figure; do NOT expand to a raw integer.",
                                                                        false)
                                        .intParam("max_iterations", "Max auto-continue iterations before stopping (0 = unlimited)",
                                                                        false)
                                        .stringParam("stop_when", "A natural-language done-condition; the agent self-completes when it is satisfied",
                                                                        false)
                                        .boolParam("continue_on_idle", "Keep working automatically until done or a limit (loop mode)",
                                                                        false)
                                        .resultRenderer(setGoalRenderer()).run((args, cwd) -> doSetGoal(args.map())));

        api.registerTool(Tool.named("goal_complete")
                                        .description("Declare the current goal/objective fully done. Stops any loop.")
                                        .resultRenderer(completeGoalRenderer()).run((args, cwd) -> doCompleteGoal()));

        api.registerStatusLines(this::statusLine);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "goal-loop-backoff");
            t.setDaemon(true);
            return t;
        });
        backoffScheduler = (r, delayMs) -> {
            if (scheduler != null && !scheduler.isShutdown())
                scheduler.schedule(r, delayMs, TimeUnit.MILLISECONDS);
        };

        api.on(ExtensionEvent.TurnStart.class, ignored -> enforceHardLimits());

        api.on(ExtensionEvent.TurnEnd.class, ignored -> {
            consecutiveFailures.set(0); // a turn completed normally. Clear the transient failure streak
            if (enforceHardLimits())
                return;
            GoalState current = state.get();
            if (current.continueOnIdle()) {
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

        // A failed turn fires Error (not TurnEnd), leaving the agent idle. For an autonomous loop, recover: back off
        // and re-prompt to wake it; give up only after MAX_CONSECUTIVE_FAILURES so a persistent outage can't spin.
        api.on(ExtensionEvent.Error.class, this::onTurnError);
    }

    private void onTurnError(ExtensionEvent.Error e) {
        GoalState current = state.get();
        if (!current.isActive() || !current.continueOnIdle())
            return;
        int fails = consecutiveFailures.incrementAndGet();
        if (fails >= MAX_CONSECUTIVE_FAILURES) {
            updateState(s -> s.markFailed(System.currentTimeMillis()));
            api.runtime().setStatus("⚠ Loop stopped: too many consecutive errors (" + fails + ")");
            return;
        }
        long delay = ErrorUtil.backoffMs(BACKOFF_BASE_MS, fails - 1, ErrorUtil.MAX_BACKOFF_MS);
        backoffScheduler.accept(() -> {
            GoalState g = state.get();
            // The agent went idle after the failure. Single-arg sendUserMessage wakes it (FOLLOW_UP only drains at a
            // turn boundary, which a failed turn never reaches).
            if (g.isActive() && g.continueOnIdle())
                api.sendUserMessage(continuationPrompt(g));
        }, delay);
    }

    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        if (scheduler != null)
            scheduler.shutdownNow();
    }

    /**
     * Apply the iteration + token-budget + time hard caps; returns true if the goal isn't active or a cap stopped it.
     */
    private boolean enforceHardLimits() {
        GoalState current = state.get();
        if (!current.isActive())
            return true;
        long now = System.currentTimeMillis();
        if (current.iterationCapReached()) {
            updateState(s -> s.markIterationLimited(now));
            return true;
        }
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
        String base = "Keep working toward the objective: " + g.objective()
                                        + ". When it is fully done, call goal_complete to stop; otherwise continue.";
        if (!g.completionCriterion().isEmpty())
            base += " It is done when: " + g.completionCriterion() + ".";
        return base;
    }

    @Override
    public void onReady(ExtensionRuntime runtime) {
        loadFromSession();
        // Resume after --continue: if a loop was active when the session was saved, nothing fires a TurnEnd, so the
        // agent would sit idle. Wake it once (single-arg) to pick the loop back up.
        GoalState restored = state.get();
        if (restored.isActive() && restored.continueOnIdle())
            api.sendUserMessage(continuationPrompt(restored));
    }

    /** The current goal state, package-private for tests (the model reads it via tools; humans via /goal). */
    GoalState currentState() {
        return state.get();
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

    // ---- slash command (/goal and /loop share one handler; /loop defaults continue-on-idle ON) ----

    private void handleCommand(String args, boolean defaultContinue) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            showGoal();
            return;
        }
        // Sub-verbs are exact matches (an objective like "pause the build" still starts a goal); progress takes a note.
        String lower = trimmed.toLowerCase();
        if (lower.equals("pause"))
            doPause();
        else if (lower.equals("resume"))
            doResume();
        else if (lower.equals("stop") || lower.equals("clear"))
            doClearOrStop();
        else if (lower.equals("progress"))
            doAddProgressFromCommand("");
        else if (lower.startsWith("progress "))
            doAddProgressFromCommand(trimmed.substring("progress ".length()).strip());
        else
            startGoal(trimmed, defaultContinue);
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
        // Surface the management actions. The command advertises "view or manage", so show how.
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

    /** Stop/clear (both verbs): clearing → idle halts the TurnEnd auto-continuation portably. */
    private void doClearOrStop() {
        GoalState current = state.get();
        if (current.status() == GoalState.Status.IDLE) {
            api.runtime().setStatus("Nothing active to stop");
            return;
        }
        boolean wasLoop = current.continueOnIdle();
        updateState(GoalState::clear);
        // Stop a loop responsively: also interrupt the in-flight turn so "/loop stop" halts everything now, not after
        // the current turn finishes generating. Only when a turn is actually running (abort while idle would arm the
        // next turn's abort flag) and only for loops. A plain /goal just tracks, so its turn is left to finish.
        if (wasLoop && !api.runtime().isIdle())
            api.runtime().abort();
        api.runtime().setStatus(wasLoop ? Emojis.PAUSE + " Loop stopped" : "Goal cleared");
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

    // ---- start (shared by /goal and /loop) ----

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

    /**
     * Shared start path for {@code /goal} and {@code /loop}: parse the objective + inline flags;
     * {@code defaultContinue} is the verb's continue-on-idle default ({@code false} for /goal, {@code true} for /loop).
     * Explicit flags always win; the config-backed cap defaults apply only when continuing and the cap is unset.
     */
    private void startGoal(String input, boolean defaultContinue) {
        Integer maxIterations = null;
        Long tokenBudget = null;
        Long maxTimeMs = null;
        Boolean continueFlag = null;
        String criterion = "";
        boolean criterionFromFlag = false;
        var words = new ArrayList<String>();
        String[] parts = input.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.equals("--continue")) {
                continueFlag = true;
            } else if (p.equals("--no-continue")) {
                continueFlag = false;
            } else if (p.equals("--max-iterations") && i + 1 < parts.length) {
                maxIterations = parseCap(parts[++i], 0);
            } else if (p.startsWith("--max-iterations=")) {
                maxIterations = parseCap(p.substring("--max-iterations=".length()), 0);
            } else if (p.equals("--max-tokens") && i + 1 < parts.length) {
                tokenBudget = parseTokens(parts[++i], 0);
            } else if (p.startsWith("--max-tokens=")) {
                tokenBudget = parseTokens(p.substring("--max-tokens=".length()), 0);
            } else if (p.equals("--max-time") && i + 1 < parts.length) {
                maxTimeMs = parseDuration(parts[++i], 0);
            } else if (p.startsWith("--max-time=")) {
                maxTimeMs = parseDuration(p.substring("--max-time=".length()), 0);
            } else if (p.startsWith("--stop-when=")) {
                criterion = stripQuotes(p.substring("--stop-when=".length()));
                criterionFromFlag = true;
            } else if (p.equals("--stop-when")) {
                // The condition is free text. Consume the rest of the line (so put --stop-when last).
                var rest = new ArrayList<String>();
                while (i + 1 < parts.length)
                    rest.add(parts[++i]);
                criterion = stripQuotes(String.join(" ", rest));
                criterionFromFlag = true;
            } else if (p.startsWith("--")) {
                api.runtime().setStatus("Unknown flag: " + p + ". " + USAGE);
                return;
            } else {
                words.add(p);
            }
        }
        String objective = String.join(" ", words).strip();
        if (!criterionFromFlag) {
            // back-compat: "objective | criterion"
            String[] split = parseObjective(objective);
            objective = split[0];
            criterion = split[1];
        }
        if (objective.isEmpty()) {
            api.runtime().setStatus(USAGE);
            return;
        }
        if (objective.length() > MAX_OBJECTIVE_LENGTH) {
            api.runtime().setStatus("Objective too long (max " + MAX_OBJECTIVE_LENGTH + " chars)");
            return;
        }
        GoalState current = state.get();
        if (current.status() == GoalState.Status.ACTIVE || current.status() == GoalState.Status.PAUSED) {
            api.runtime().setStatus("A goal/loop is already active. Use /loop stop or /goal clear first.");
            return;
        }
        boolean cont = continueFlag != null ? continueFlag : defaultContinue;
        // Config-backed defaults apply only to a continuing goal with the cap left unset; explicit flags always win.
        int cap = maxIterations != null ? maxIterations : (cont ? defaultMaxIterations() : 0);
        long budget = tokenBudget != null ? tokenBudget : (cont ? defaultMaxTokens() : 0);
        long timeMs = maxTimeMs != null ? maxTimeMs : (cont ? defaultMaxTimeMs() : 0);
        String crit = criterion;
        String obj = objective;
        long now = System.currentTimeMillis();
        var llm = api.runtime().llm();
        GoalState next = GoalState.start(obj, budget, crit, cont, cap, timeMs, llm.totalInputTokens(),
                                        llm.totalOutputTokens(), now);
        updateState(s -> next);
        api.runtime().setStatus(Emojis.DART + " " + (cont ? "Loop: " : "Goal: ") + objective
                                        + loopCapsSummary(cap, budget, timeMs));
        api.sendUserMessage(continuationPrompt(next));
    }

    private static final String USAGE = "Usage: /goal|/loop <objective> [--max-iterations N] [--max-tokens N] [--max-time 30m] [--stop-when \"…\"] [--continue|--no-continue]";

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

    /** Parse a token budget: {@code 500k}, {@code 1M}, {@code 2.5M}, {@code 1_000_000}, or a raw integer. */
    static long parseTokens(Object value, long fallback) {
        if (value == null)
            return fallback;
        String v = String.valueOf(value).strip().toLowerCase().replace("_", "").replace(",", "");
        if (v.isEmpty())
            return fallback;
        double mult = 1;
        char last = v.charAt(v.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = switch (last) {
                case 'k' -> 1_000d;
                case 'm' -> 1_000_000d;
                default -> 1_000_000_000d;
            };
            v = v.substring(0, v.length() - 1).strip();
        }
        try {
            double n = Double.parseDouble(v);
            return n > 0 ? Math.max(0, (long) (n * mult)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Parse a duration like {@code 90s}, {@code 30m}, {@code 2h}, {@code 1h30m}; a bare number is seconds. ms result.
     */
    static long parseDuration(Object value, long fallback) {
        if (value == null)
            return fallback;
        String v = String.valueOf(value).strip().toLowerCase();
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
        return api.runtime().getFlag("loop-max-tokens").map(v -> parseTokens(v, 0L)).orElse(0L);
    }

    private long defaultMaxTimeMs() {
        return api.runtime().getFlag("loop-max-time").map(v -> parseDuration(v, 0L)).orElse(0L);
    }

    // ---- model tools (just two: set the objective, declare it done) ----

    private Tool.ToolResult doSetGoal(Map<String, Object> args) {
        String objective = (String) args.get("objective");
        if (objective == null || objective.isBlank())
            return Tool.ToolResult.error("objective is required");
        if (objective.length() > MAX_OBJECTIVE_LENGTH)
            return Tool.ToolResult.error("objective exceeds " + MAX_OBJECTIVE_LENGTH + " chars");
        long tokenBudget = parseTokens(args.get("max_tokens"), 0); // forgiving strings: the model copies, we parse
        long maxTimeMs = parseDuration(args.get("max_time"), 0);
        Number maxIter = (Number) args.get("max_iterations");
        int maxIterations = maxIter != null ? Math.max(0, maxIter.intValue()) : 0;
        String criterion = args.get("stop_when") instanceof String s ? s : "";
        boolean continueOnIdle = Boolean.TRUE.equals(args.get("continue_on_idle"));
        long now = System.currentTimeMillis();
        var llm = api.runtime().llm();
        GoalState next = GoalState.start(objective, tokenBudget, criterion, continueOnIdle, maxIterations, maxTimeMs,
                                        llm.totalInputTokens(), llm.totalOutputTokens(), now);
        consecutiveFailures.set(0);
        updateState(s -> next);
        return Tool.ToolResult.success(renderGoal(next));
    }

    private Tool.ToolResult doCompleteGoal() {
        if (state.get().status() == GoalState.Status.IDLE)
            return Tool.ToolResult.error("No active goal to complete");
        updateState(s -> s.complete(System.currentTimeMillis()));
        return Tool.ToolResult.success(renderGoal(state.get()));
    }

    // ---- internals ----

    private void saveEntry(GoalState goalState) {
        boolean idle = goalState.status() == GoalState.Status.IDLE;
        // These entries are state snapshots persisted for reload, shown via the status line, not as repeated
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
        int fails = consecutiveFailures.get();
        if (fails > 0 && current.isActive())
            sb.append(" · retrying… (").append(fails).append('/').append(MAX_CONSECUTIVE_FAILURES).append(')');
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
            case FAILED -> "failed";
            case IDLE -> "idle";
        };
    }

    private static Tool.ResultRenderer setGoalRenderer() {
        return (args, result, cwd) -> {
            if (result.isError())
                return Tool.ToolResultView.of("Failed to set goal");
            String objective = String.valueOf(args.getOrDefault("objective", ""));
            return Tool.ToolResultView.of("Goal set: " + Util.truncate(objective, 60));
        };
    }

    private static Tool.ResultRenderer completeGoalRenderer() {
        return (args, result, cwd) -> Tool.ToolResultView
                                        .of(result.isError() ? "No goal to complete" : "Goal completed");
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
