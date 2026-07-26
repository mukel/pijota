package org.pijota.extensions.examples;

import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.messages.Message;
import org.pijota.tools.Tool;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalExtensionTest {

    private static GoalExtension started(FakeExtensionAPI fake) {
        var ext = new GoalExtension();
        ext.init(fake);
        return ext;
    }

    private static Tool.ToolResult execute(Tool.ToolDefinition tool, Map<String, Object> args) {
        return tool.execute().execute("id", new Tool.Args(args), ".").join();
    }

    @Test
    void nameAndDescriptionAreSet() {
        var ext = new GoalExtension();
        assertEquals("goal", ext.name());
        assertFalse(ext.description().isBlank());
    }

    // ---- model tool surface: exactly two, single-purpose ----

    @Test
    void modelToolSurfaceIsExactlySetAndComplete() {
        var fake = new FakeExtensionAPI();
        started(fake);
        var names = fake.tools().stream().map(Tool.ToolDefinition::name).sorted().toList();
        assertEquals(List.of("goal_complete", "goal_set"), names);
    }

    @Test
    void setGoalStartsActiveGoal() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        var result = execute(fake.tool("goal_set"), Map.of("objective", "write tests"));
        assertFalse(result.isError());
        assertEquals("write tests", ext.currentState().objective());
        assertEquals(GoalState.Status.ACTIVE, ext.currentState().status());
    }

    @Test
    void setGoalRequiresObjective() {
        var fake = new FakeExtensionAPI();
        started(fake);
        assertTrue(execute(fake.tool("goal_set"), Map.of("objective", "")).isError());
    }

    @Test
    void setGoalReplacesAnExistingGoal() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        execute(fake.tool("goal_set"), Map.of("objective", "first"));
        var result = execute(fake.tool("goal_set"), Map.of("objective", "second"));
        assertFalse(result.isError());
        assertEquals("second", ext.currentState().objective());
    }

    @Test
    void setGoalParsesHumanFriendlyStrings() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        execute(fake.tool("goal_set"), Map.of("objective", "x", "max_time", "30m", "max_tokens", "1M", "max_iterations",
                                        50, "continue_on_idle", true));
        var g = ext.currentState();
        assertEquals(1_800_000L, g.maxTimeMs(), "30m → 1800000ms");
        assertEquals(1_000_000L, g.tokenBudget(), "1M → 1000000 tokens");
        assertEquals(50, g.maxIterations());
        assertTrue(g.continueOnIdle());
    }

    @Test
    void setGoalRendererSummary() {
        var fake = new FakeExtensionAPI();
        started(fake);
        var tool = fake.tool("goal_set").toTool();
        var result = tool.execute("id", new Tool.Args(Map.of("objective", "write tests")), ".").join();
        var view = tool.renderResult(new Tool.Args(Map.of("objective", "write tests")), result, ".");
        assertTrue(view.summary().contains("write tests"));
    }

    @Test
    void completeGoalEndsTheLoop() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("loop").handler().accept("do it");
        var result = execute(fake.tool("goal_complete"), Map.of());
        assertFalse(result.isError());
        assertEquals(GoalState.Status.COMPLETE, ext.currentState().status());
    }

    @Test
    void completeGoalWithNoGoalErrors() {
        var fake = new FakeExtensionAPI();
        started(fake);
        assertTrue(execute(fake.tool("goal_complete"), Map.of()).isError());
    }

    @Test
    void parseTokensHandlesSuffixes() {
        assertEquals(500_000L, GoalExtension.parseTokens("500k", 0));
        assertEquals(1_000_000L, GoalExtension.parseTokens("1M", 0));
        assertEquals(2_500_000L, GoalExtension.parseTokens("2.5M", 0));
        assertEquals(1_000_000L, GoalExtension.parseTokens("1_000_000", 0));
        assertEquals(1234L, GoalExtension.parseTokens("1234", 0));
        assertEquals(7L, GoalExtension.parseTokens("oops", 7));
    }

    @Test
    void parseDurationHandlesUnits() {
        assertEquals(90_000L, GoalExtension.parseDuration("90s", 0));
        assertEquals(1_800_000L, GoalExtension.parseDuration("30m", 0));
        assertEquals(7_200_000L, GoalExtension.parseDuration("2h", 0));
        assertEquals(5_400_000L, GoalExtension.parseDuration("1h30m", 0));
        assertEquals(45_000L, GoalExtension.parseDuration("45", 0)); // bare = seconds
    }

    // ---- long-running robustness ----

    @Test
    void loopRecoversFromTransientFailuresThenGivesUp() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        ext.backoffScheduler = (r, d) -> r.run(); // run the backed-off re-prompt synchronously
        fake.slashCommand("loop").handler().accept("keep going");
        for (int i = 0; i < 4; i++)
            fake.fireError("boom", true);
        assertEquals(GoalState.Status.ACTIVE, ext.currentState().status(), "recovers from transient failures");
        assertTrue(fake.sentUserMessages().stream().anyMatch(m -> m.contains("Keep working")), "recovery re-prompts");
        fake.fireError("boom", true); // 5th consecutive → give up
        assertEquals(GoalState.Status.FAILED, ext.currentState().status());
    }

    @Test
    void aSuccessfulTurnResetsTheFailureStreak() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        ext.backoffScheduler = (r, d) -> {}; // don't re-prompt; just count
        fake.slashCommand("loop").handler().accept("keep going");
        for (int i = 0; i < 4; i++)
            fake.fireError("boom", true);
        fake.fireTurnEnd(1); // a turn succeeded → streak resets
        for (int i = 0; i < 4; i++)
            fake.fireError("boom", true);
        assertEquals(GoalState.Status.ACTIVE, ext.currentState().status(), "streak reset — not failed");
    }

    @Test
    void errorsDoNotAffectANonLoopGoal() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        ext.backoffScheduler = (r, d) -> r.run();
        fake.slashCommand("goal").handler().accept("just tracking"); // continue OFF
        int prompts = fake.sentUserMessages().size();
        for (int i = 0; i < 10; i++)
            fake.fireError("boom", true);
        assertEquals(GoalState.Status.ACTIVE, ext.currentState().status());
        assertEquals(prompts, fake.sentUserMessages().size(), "a non-continuing goal isn't re-prompted on errors");
    }

    @Test
    void onReadyResumesAnActiveContinuingLoop() {
        var fake = new FakeExtensionAPI();
        fake.seedMessage(new Message.Custom("goal",
                                        "{\"objective\":\"resumed\",\"status\":\"active\",\"continue_on_idle\":true,\"started_at_ms\":1000,\"paused_at_ms\":-1,\"progress\":[]}"));
        var ext = started(fake);
        ext.onReady(fake.runtime());
        assertEquals("resumed", ext.currentState().objective());
        assertTrue(fake.sentUserMessages().stream().anyMatch(m -> m.contains("resumed")), "resume kicked the loop");
    }

    @Test
    void onReadyDoesNotResumeATrackingGoal() {
        var fake = new FakeExtensionAPI();
        fake.seedMessage(new Message.Custom("goal",
                                        "{\"objective\":\"track\",\"status\":\"active\",\"continue_on_idle\":false,\"started_at_ms\":1000,\"paused_at_ms\":-1,\"progress\":[]}"));
        var ext = started(fake);
        ext.onReady(fake.runtime());
        assertTrue(fake.sentUserMessages().isEmpty(), "a non-loop goal isn't auto-resumed");
    }

    // ---- continuation / caps ----

    @Test
    void loopRePromptsViaFollowUpUnderTheCap() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("loop").handler().accept("tidy the readme");
        assertTrue(ext.currentState().continueOnIdle());
        fake.fireTurnEnd(1);
        assertTrue(fake.sentUserMessages().stream().anyMatch(m -> m.contains("FOLLOW_UP")),
                                        "continuation re-prompts via FOLLOW_UP: " + fake.sentUserMessages());
        assertEquals(GoalState.Status.ACTIVE, ext.currentState().status());
    }

    @Test
    void loopStopsAtTheIterationCap() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        execute(fake.tool("goal_set"), Map.of("objective", "x", "continue_on_idle", true, "max_iterations", 1));
        fake.fireTurnEnd(1); // 0 → 1, one continuation
        assertEquals(1, fake.sentUserMessages().size());
        fake.fireTurnEnd(2); // cap reached → iteration-limited
        assertEquals(1, fake.sentUserMessages().size());
        assertEquals(GoalState.Status.ITERATION_LIMITED, ext.currentState().status());
    }

    @Test
    void budgetLimitIsEnforcedAtTurnEnd() {
        var fake = new FakeExtensionAPI();
        fake.tokens(0, 0);
        var ext = started(fake);
        execute(fake.tool("goal_set"), Map.of("objective", "x", "max_tokens", "100"));
        fake.tokens(60, 50); // 110 > 100
        fake.fireTurnEnd(1);
        assertEquals(GoalState.Status.BUDGET_LIMITED, ext.currentState().status());
    }

    @Test
    void continueOnIdleIsDebounced() throws InterruptedException {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        execute(fake.tool("goal_set"), Map.of("objective", "x", "continue_on_idle", true));
        fake.fireTurnEnd(1);
        fake.fireTurnEnd(2);
        fake.fireTurnEnd(3);
        assertEquals(1, fake.sentUserMessages().size());
        Thread.sleep(1100);
        fake.fireTurnEnd(4);
        assertEquals(2, fake.sentUserMessages().size());
        assertNotNull(ext);
    }

    @Test
    void loopStopHaltsContinuation() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("loop").handler().accept("keep going");
        fake.slashCommand("loop").handler().accept("stop");
        assertEquals(GoalState.Status.IDLE, ext.currentState().status());
        int prompts = fake.sentUserMessages().size();
        fake.fireTurnEnd(1);
        assertEquals(prompts, fake.sentUserMessages().size(), "a stopped loop must not re-prompt");
    }

    // ---- human slash verbs (shared by /goal and /loop) ----

    @Test
    void slashGoalCreatesGoal() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("goal").handler().accept("implement feature X");
        assertEquals("implement feature X", ext.currentState().objective());
        assertFalse(ext.currentState().continueOnIdle(), "/goal does not loop after the first continuation");
    }

    @Test
    void slashGoalImmediatelyPromptsAgentToWork() {
        var fake = new FakeExtensionAPI();
        started(fake);
        fake.slashCommand("goal").handler().accept("implement feature X");
        assertTrue(fake.sentUserMessages().stream().anyMatch(m -> m.contains("implement feature X")),
                                        "/goal should start work, not only register state");
    }

    @Test
    void slashGoalContinueFlagTurnsOnTheLoop() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("goal").handler().accept("ship it --continue");
        assertTrue(ext.currentState().continueOnIdle());
    }

    @Test
    void slashPauseAndResume() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("goal").handler().accept("x");
        fake.slashCommand("goal").handler().accept("pause");
        assertEquals(GoalState.Status.PAUSED, ext.currentState().status());
        fake.slashCommand("goal").handler().accept("resume");
        assertEquals(GoalState.Status.ACTIVE, ext.currentState().status());
    }

    @Test
    void slashProgressAddsEntry() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("goal").handler().accept("x");
        fake.slashCommand("goal").handler().accept("progress finished setup");
        assertTrue(ext.currentState().progress().stream().anyMatch(p -> p.contains("finished setup")));
    }

    @Test
    void slashStopOnGoalClearsToIdle() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        fake.slashCommand("goal").handler().accept("x");
        fake.slashCommand("goal").handler().accept("stop");
        assertEquals(GoalState.Status.IDLE, ext.currentState().status());
    }

    @Test
    void clearCommandResetsToIdleWithoutNpe() {
        var fake = new FakeExtensionAPI();
        var ext = started(fake);
        var goalCmd = fake.slashCommand("goal");
        goalCmd.handler().accept("write the docs");
        assertDoesNotThrow(() -> goalCmd.handler().accept("clear"));
        assertEquals(GoalState.Status.IDLE, ext.currentState().status());
    }

    // ---- pure helpers ----

    @Test
    void statusLabelMapsAllStatuses() {
        assertEquals("active", GoalExtension.statusLabel(GoalState.Status.ACTIVE));
        assertEquals("paused", GoalExtension.statusLabel(GoalState.Status.PAUSED));
        assertEquals("complete", GoalExtension.statusLabel(GoalState.Status.COMPLETE));
        assertEquals("budget-limited", GoalExtension.statusLabel(GoalState.Status.BUDGET_LIMITED));
        assertEquals("iteration-limited", GoalExtension.statusLabel(GoalState.Status.ITERATION_LIMITED));
        assertEquals("time-limited", GoalExtension.statusLabel(GoalState.Status.TIME_LIMITED));
        assertEquals("failed", GoalExtension.statusLabel(GoalState.Status.FAILED));
        assertEquals("idle", GoalExtension.statusLabel(GoalState.Status.IDLE));
    }

    @Test
    void parseObjectiveSplitsOnPipe() {
        var result = GoalExtension.parseObjective("Do something | when done");
        assertEquals("Do something", result[0]);
        assertEquals("when done", result[1]);
    }

    @Test
    void parseObjectiveNoCriterion() {
        var result = GoalExtension.parseObjective("Just an objective");
        assertEquals("Just an objective", result[0]);
        assertEquals("", result[1]);
    }
}
