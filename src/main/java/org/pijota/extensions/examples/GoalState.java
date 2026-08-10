package org.pijota.extensions.examples;

import org.pijota.util.Util;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable state for the goal extension. All mutations return a new {@link GoalState}.
 */
record GoalState(String objective, Status status, long tokenBudget, String completionCriterion, boolean continueOnIdle,
                                long inputTokensAtStart, long outputTokensAtStart, long startedAtMs, long totalPausedMs,
                                long pausedAtMs, List<String> progress, int maxIterations, int iterations,
                                long maxTimeMs) {

    enum Status {
        IDLE, ACTIVE, PAUSED, COMPLETE, BUDGET_LIMITED, ITERATION_LIMITED, TIME_LIMITED, FAILED
    }

    private static final long MAX_OBJECTIVE_LENGTH = 8000;
    private static final int MAX_PROGRESS_ENTRIES = 20;

    static GoalState idle() {
        return new GoalState("", Status.IDLE, 0, "", false, 0, 0, 0, 0, -1, List.of(), 0, 0, 0);
    }

    /**
     * What stops a goal: a token budget, a cap on auto-continue iterations, a wall-clock limit. Zero means no limit for
     * each, independently.
     *
     * <p>
     * One value rather than three numbers spread through three overloads of {@code start}. Those overloads differed
     * only in which of these they defaulted, so a call read {@code start("x", 0, "", true, 2, 0, 0, 0)} and nothing in
     * it said which zero was a budget, which a cap and which a starting token count. Swapping two of them compiles and
     * changes when the loop stops.
     */
    record Limits(long tokenBudget, int maxIterations, long maxTimeMs) {
        static final Limits NONE = new Limits(0, 0, 0);

        static Limits ofTokens(long tokenBudget) {
            return new Limits(tokenBudget, 0, 0);
        }
    }

    static GoalState start(String objective, String completionCriterion, boolean continueOnIdle, Limits limits,
                                    long inputTokensAtStart, long outputTokensAtStart, long nowMs) {
        Objects.requireNonNull(objective, "objective");
        if (objective.isBlank())
            throw new IllegalArgumentException("objective is required");
        if (objective.length() > MAX_OBJECTIVE_LENGTH)
            throw new IllegalArgumentException("objective exceeds " + MAX_OBJECTIVE_LENGTH + " chars");
        String criterion = completionCriterion == null ? "" : completionCriterion;
        return new GoalState(objective.strip(), Status.ACTIVE, Math.max(0, limits.tokenBudget()), criterion.strip(),
                                        continueOnIdle, Math.max(0, inputTokensAtStart),
                                        Math.max(0, outputTokensAtStart), nowMs, 0, -1, List.of(),
                                        Math.max(0, limits.maxIterations()), 0, Math.max(0, limits.maxTimeMs()));
    }

    GoalState pause(long nowMs) {
        if (status != Status.ACTIVE && status != Status.BUDGET_LIMITED)
            throw new IllegalStateException("Cannot pause goal in state " + status);
        return new GoalState(objective, Status.PAUSED, tokenBudget, completionCriterion, continueOnIdle,
                                        inputTokensAtStart, outputTokensAtStart, startedAtMs, totalPausedMs, nowMs,
                                        progress, maxIterations, iterations, maxTimeMs);
    }

    GoalState resume(long nowMs) {
        if (status != Status.PAUSED)
            throw new IllegalStateException("Cannot resume goal in state " + status);
        long additionalPause = pausedAtMs > 0 ? Math.max(0, nowMs - pausedAtMs) : 0;
        return new GoalState(objective, Status.ACTIVE, tokenBudget, completionCriterion, continueOnIdle,
                                        inputTokensAtStart, outputTokensAtStart, startedAtMs,
                                        totalPausedMs + additionalPause, -1, progress, maxIterations, iterations,
                                        maxTimeMs);
    }

    GoalState complete(long nowMs) {
        if (status == Status.IDLE)
            throw new IllegalStateException("Cannot complete an idle goal");
        return withStatus(Status.COMPLETE, nowMs);
    }

    GoalState markBudgetLimited(long nowMs) {
        if (status != Status.ACTIVE)
            return this;
        return withStatus(Status.BUDGET_LIMITED, nowMs);
    }

    GoalState markIterationLimited(long nowMs) {
        if (status != Status.ACTIVE)
            return this;
        return withStatus(Status.ITERATION_LIMITED, nowMs);
    }

    GoalState markTimeLimited(long nowMs) {
        if (status != Status.ACTIVE)
            return this;
        return withStatus(Status.TIME_LIMITED, nowMs);
    }

    GoalState markFailed(long nowMs) {
        if (status != Status.ACTIVE)
            return this;
        return withStatus(Status.FAILED, nowMs);
    }

    GoalState incrementIteration() {
        return new GoalState(objective, status, tokenBudget, completionCriterion, continueOnIdle, inputTokensAtStart,
                                        outputTokensAtStart, startedAtMs, totalPausedMs, pausedAtMs, progress,
                                        maxIterations, iterations + 1, maxTimeMs);
    }

    /** True when a positive iteration cap has been reached (0 = unlimited, never reached). */
    boolean iterationCapReached() {
        return maxIterations > 0 && iterations >= maxIterations;
    }

    GoalState clear() {
        return idle();
    }

    GoalState addProgress(String note, long nowMs) {
        Objects.requireNonNull(note, "note");
        if (note.isBlank())
            throw new IllegalArgumentException("progress note is required");
        var updated = new ArrayList<>(progress);
        updated.add(formatProgressEntry(note, nowMs));
        while (updated.size() > MAX_PROGRESS_ENTRIES)
            updated.removeFirst();
        return new GoalState(objective, status, tokenBudget, completionCriterion, continueOnIdle, inputTokensAtStart,
                                        outputTokensAtStart, startedAtMs, totalPausedMs, pausedAtMs,
                                        Collections.unmodifiableList(updated), maxIterations, iterations, maxTimeMs);
    }

    boolean isActive() {
        return status == Status.ACTIVE;
    }

    boolean isTerminal() {
        return status == Status.COMPLETE || status == Status.IDLE;
    }

    boolean hasBudget() {
        return tokenBudget > 0;
    }

    boolean hasTimeLimit() {
        return maxTimeMs > 0;
    }

    long elapsedMs(long nowMs) {
        if (startedAtMs <= 0)
            return 0;
        long endMs = (status == Status.PAUSED && pausedAtMs > 0) ? pausedAtMs : nowMs;
        return Math.max(0, endMs - startedAtMs - totalPausedMs);
    }

    /** Wall-clock budget remaining, paused-time-aware (−1 = no time limit). */
    long remainingMs(long nowMs) {
        return hasTimeLimit() ? Math.max(0, maxTimeMs - elapsedMs(nowMs)) : -1;
    }

    /** True once the (paused-time-aware) elapsed time has reached the time budget. */
    boolean timeExceeded(long nowMs) {
        return hasTimeLimit() && elapsedMs(nowMs) >= maxTimeMs;
    }

    long currentUsage(long totalInputTokens, long totalOutputTokens) {
        long in = Math.max(0, totalInputTokens - inputTokensAtStart);
        long out = Math.max(0, totalOutputTokens - outputTokensAtStart);
        return in + out;
    }

    boolean wouldExceedBudget(long totalInputTokens, long totalOutputTokens) {
        if (!hasBudget())
            return false;
        return currentUsage(totalInputTokens, totalOutputTokens) >= tokenBudget;
    }

    String toJson() {
        var node = Util.MAPPER.createObjectNode();
        node.put("objective", objective == null ? "" : objective);
        node.put("status", status.name().toLowerCase());
        node.put("token_budget", tokenBudget);
        node.put("completion_criterion", completionCriterion == null ? "" : completionCriterion);
        node.put("continue_on_idle", continueOnIdle);
        node.put("input_tokens_at_start", inputTokensAtStart);
        node.put("output_tokens_at_start", outputTokensAtStart);
        node.put("started_at_ms", startedAtMs);
        node.put("total_paused_ms", totalPausedMs);
        node.put("paused_at_ms", pausedAtMs);
        node.put("max_iterations", maxIterations);
        node.put("iterations", iterations);
        node.put("max_time_ms", maxTimeMs);
        var progressNode = node.putArray("progress");
        for (String entry : progress)
            progressNode.add(entry);
        return node.toString();
    }

    static GoalState fromJson(String json) {
        if (json == null || json.isBlank())
            return idle();
        try {
            var node = Util.MAPPER.readTree(json);
            String statusText = node.has("status") ? node.get("status").asText("idle") : "idle";
            Status parsed = parseStatus(statusText);
            if (parsed == Status.IDLE)
                return idle();
            var progress = new ArrayList<String>();
            if (node.has("progress") && node.get("progress").isArray()) {
                for (var entry : node.get("progress"))
                    progress.add(entry.asText(""));
            }
            return new GoalState(node.has("objective") ? node.get("objective").asText("") : "", parsed,
                                            node.has("token_budget") ? node.get("token_budget").asLong(0) : 0,
                                            node.has("completion_criterion")
                                                                            ? node.get("completion_criterion")
                                                                                                            .asText("")
                                                                            : "",
                                            node.has("continue_on_idle") && node.get("continue_on_idle")
                                                                            .asBoolean(false),
                                            node.has("input_tokens_at_start")
                                                                            ? node.get("input_tokens_at_start")
                                                                                                            .asLong(0)
                                                                            : 0,
                                            node.has("output_tokens_at_start")
                                                                            ? node.get("output_tokens_at_start")
                                                                                                            .asLong(0)
                                                                            : 0,
                                            node.has("started_at_ms") ? node.get("started_at_ms").asLong(0) : 0,
                                            node.has("total_paused_ms") ? node.get("total_paused_ms").asLong(0) : 0,
                                            node.has("paused_at_ms") ? node.get("paused_at_ms").asLong(-1) : -1,
                                            Collections.unmodifiableList(progress),
                                            node.has("max_iterations") ? node.get("max_iterations").asInt(0) : 0,
                                            node.has("iterations") ? node.get("iterations").asInt(0) : 0,
                                            node.has("max_time_ms") ? node.get("max_time_ms").asLong(0) : 0);
        } catch (Exception e) {
            return idle();
        }
    }

    private GoalState withStatus(Status newStatus, long nowMs) {
        if (status == Status.PAUSED && pausedAtMs > 0 && newStatus != Status.PAUSED) {
            long additionalPause = Math.max(0, nowMs - pausedAtMs);
            return new GoalState(objective, newStatus, tokenBudget, completionCriterion, continueOnIdle,
                                            inputTokensAtStart, outputTokensAtStart, startedAtMs,
                                            totalPausedMs + additionalPause, -1, progress, maxIterations, iterations,
                                            maxTimeMs);
        }
        return new GoalState(objective, newStatus, tokenBudget, completionCriterion, continueOnIdle, inputTokensAtStart,
                                        outputTokensAtStart, startedAtMs, totalPausedMs, pausedAtMs, progress,
                                        maxIterations, iterations, maxTimeMs);
    }

    private static String formatProgressEntry(String note, long nowMs) {
        return Instant.ofEpochMilli(nowMs).toString() + "  " + note.strip();
    }

    private static Status parseStatus(String s) {
        return switch (s) {
            case "active" -> Status.ACTIVE;
            case "paused" -> Status.PAUSED;
            case "complete" -> Status.COMPLETE;
            case "budget-limited", "budget_limited" -> Status.BUDGET_LIMITED;
            case "iteration-limited", "iteration_limited" -> Status.ITERATION_LIMITED;
            case "time-limited", "time_limited" -> Status.TIME_LIMITED;
            default -> Status.IDLE;
        };
    }
}
