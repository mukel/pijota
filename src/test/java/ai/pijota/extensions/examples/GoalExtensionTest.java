package ai.pijota.extensions.examples;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalExtensionTest {

    @Test
    void nameAndDescriptionAreSet() {
        var ext = new GoalExtension();
        assertEquals("goal", ext.name());
        assertFalse(ext.description().isBlank());
    }

    @Test
    void goalSlashCommandRendersTheActiveGoal() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "ship the feature"));

        fake.slashCommand("goal").handler().accept(""); // /goal with no args → showGoal

        assertTrue(fake.sentMessages().stream()
                                        .anyMatch(m -> m.content() != null && m.content().contains("ship the feature")),
                                        fake.sentMessages().toString());
    }

    @Test
    void createGoalToolStartsActiveGoal() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);

        var result = execute(fake.tool("create_goal"), Map.of("objective", "write tests"));

        assertFalse(result.isError());
        assertTrue(fake.lastSentMessage().contains("write tests"));
        assertTrue(fake.status().contains("write tests"));
    }

    @Test
    void createGoalToolRendersSummary() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        var tool = fake.tool("create_goal").toTool();

        var result = tool.execute("id", new Tool.Args(Map.of("objective", "write tests")), ".").join();
        var view = tool.renderResult(new Tool.Args(Map.of("objective", "write tests")), result, ".");

        assertTrue(view.summary().contains("Created goal: write tests"));
    }

    @Test
    void createGoalRendererHandlesErrors() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        var tool = fake.tool("create_goal").toTool();

        var result = tool.execute("id", new Tool.Args(Map.of("objective", "")), ".").join();
        var view = tool.renderResult(new Tool.Args(Map.of("objective", "")), result, ".");

        assertTrue(view.summary().contains("Failed"));
    }

    @Test
    void createGoalFailsWithoutReplaceWhenGoalExists() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "first"));

        var result = execute(fake.tool("create_goal"), Map.of("objective", "second"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("already exists"));
    }

    @Test
    void createGoalWithReplaceOverridesExisting() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "first"));

        var result = execute(fake.tool("create_goal"), Map.of("objective", "second", "replace_existing", true));

        assertFalse(result.isError());
        assertTrue(result.content().contains("second"));
    }

    @Test
    void getGoalReturnsCurrentState() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "token_budget", 500));

        var result = execute(fake.tool("get_goal"), Map.of());

        assertFalse(result.isError());
        assertTrue(result.content().contains("objective: x"));
        assertTrue(result.content().contains("usage: 0/500"));
    }

    @Test
    void updateGoalCanComplete() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x"));

        var result = execute(fake.tool("update_goal"), Map.of("status", "complete"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("status: complete"));
    }

    @Test
    void pauseAndResumeSwitchesStatusCorrectly() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x"));
        execute(fake.tool("update_goal"), Map.of("status", "pause"));
        execute(fake.tool("update_goal"), Map.of("status", "resume"));

        var get = execute(fake.tool("get_goal"), Map.of());
        assertFalse(get.isError());
        assertTrue(get.content().contains("status: active"),
                                        "expected active status after resume, got: " + get.content());
    }

    @Test
    void addProgressAppendsNote() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x"));

        var result = execute(fake.tool("add_goal_progress"), Map.of("note", "done step 1"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("done step 1"));
    }

    @Test
    void budgetLimitIsEnforcedAtTurnEnd() {
        var fake = new FakeExtensionAPI();
        fake.tokens(0, 0);
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "token_budget", 100));

        fake.tokens(60, 50); // 110 total, exceeds 100
        fake.fireTurnEnd(1);

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("status: budget-limited"));
    }

    @Test
    void budgetLimitIsEnforcedAtTurnStart() {
        var fake = new FakeExtensionAPI();
        fake.tokens(0, 0);
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "token_budget", 100));

        fake.tokens(60, 50); // 110 total, exceeds 100
        fake.fireTurnStart(1);

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("status: budget-limited"));
    }

    @Test
    void continueOnIdleIsDebounced() throws InterruptedException {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "continue_on_idle", true));

        fake.fireTurnEnd(1);
        fake.fireTurnEnd(2);
        fake.fireTurnEnd(3);

        assertEquals(1, fake.sentUserMessages().size());

        Thread.sleep(1100);
        fake.fireTurnEnd(4);

        assertEquals(2, fake.sentUserMessages().size());
    }

    @Test
    void slashCommandCreatesGoal() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);

        fake.slashCommand("goal").handler().accept("implement feature X");

        assertTrue(fake.status().contains("implement feature X"));
    }

    @Test
    void slashCommandPauseAndResumeWork() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("x");

        fake.slashCommand("goal").handler().accept("pause");
        assertTrue(fake.status().contains("paused"));

        fake.slashCommand("goal").handler().accept("resume");
        assertTrue(fake.status().contains("resumed"));
    }

    @Test
    void slashCommandProgressAddsEntry() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("x");

        fake.slashCommand("goal").handler().accept("progress finished setup");

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("finished setup"));
    }

    @Test
    void sessionReadyRestoresLatestGoal() {
        var fake = new FakeExtensionAPI();
        fake.seedMessage(new Message.Custom("goal",
                                        "{\"objective\":\"restored\",\"status\":\"active\",\"token_budget\":0,\"completion_criterion\":\"\",\"continue_on_idle\":false,\"input_tokens_at_start\":0,\"output_tokens_at_start\":0,\"started_at_ms\":1000,\"total_paused_ms\":0,\"paused_at_ms\":-1,\"progress\":[]}"));
        var goalExt = new GoalExtension();
        goalExt.init(fake);
        goalExt.onReady(fake.runtime());

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("restored"));
    }

    @Test
    void sessionReadyRestoresClearedGoalAsIdle() {
        var fake = new FakeExtensionAPI();
        fake.seedMessage(new Message.Custom("goal",
                                        "{\"objective\":\"old\",\"status\":\"active\",\"token_budget\":0,\"completion_criterion\":\"\",\"continue_on_idle\":false,\"input_tokens_at_start\":0,\"output_tokens_at_start\":0,\"started_at_ms\":1000,\"total_paused_ms\":0,\"paused_at_ms\":-1,\"progress\":[]}"));
        fake.seedMessage(new Message.Custom("goal", "{\"status\":\"idle\"}"));
        var goalExt = new GoalExtension();
        goalExt.init(fake);
        goalExt.onReady(fake.runtime());

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("status: idle"));
    }

    @Test
    void loopSlashCommandStartsAContinuingGoalThatRePromptsViaFollowUp() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);

        fake.slashCommand("loop").handler().accept("tidy the readme");
        assertTrue(fake.status().contains("tidy the readme"), fake.status());

        fake.fireTurnEnd(1);
        assertTrue(fake.sentUserMessages().stream().anyMatch(m -> m.contains("FOLLOW_UP")),
                                        "continuation must re-prompt via FOLLOW_UP: " + fake.sentUserMessages());
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("status: active"));
    }

    @Test
    void loopStopsAtTheIterationCap() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "continue_on_idle", true, "max_iterations", 1));

        fake.fireTurnEnd(1); // iterations 0 -> 1, sends one continuation
        assertEquals(1, fake.sentUserMessages().size());

        fake.fireTurnEnd(2); // cap reached (1 >= 1) -> iteration-limited, no further continuation
        assertEquals(1, fake.sentUserMessages().size());
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("status: iteration-limited"));
    }

    @Test
    void loopStopHaltsContinuation() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("loop").handler().accept("keep going");

        fake.slashCommand("loop").handler().accept("stop");
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("status: idle"));

        fake.fireTurnEnd(1);
        assertTrue(fake.sentUserMessages().isEmpty(), "a stopped loop must not re-prompt: " + fake.sentUserMessages());
    }

    @Test
    void loopParsesTokenTimeAndStopWhenFlags() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("loop").handler().accept(
                                        "refactor X --max-tokens 100 --max-time 30m --stop-when \"tests pass\"");
        var content = execute(fake.tool("get_goal"), Map.of()).content();
        assertTrue(content.contains("/100"), content); // token budget
        assertTrue(content.contains("time left"), content); // time limit set
        assertTrue(content.contains("tests pass"), content); // stop-when criterion
    }

    @Test
    void loopContinuationIncludesStopWhenCriterion() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("loop").handler().accept("ship it --stop-when all tests green");
        fake.fireTurnEnd(1);
        assertTrue(fake.sentUserMessages().stream().anyMatch(m -> m.contains("all tests green")),
                                        fake.sentUserMessages().toString());
    }

    @Test
    void createGoalHonorsMaxSeconds() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "max_seconds", 1800));
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("time left"));
    }

    @Test
    void loopUsesDefaultIterationCapWhenNoFlag() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("loop").handler().accept("just loop");
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("0/25"));
    }

    @Test
    void loopMaxTokensDefaultComesFromConfigFlag() {
        var fake = new FakeExtensionAPI();
        ((FakeExtensionRuntime) fake.runtime()).setFlag("loop-max-tokens", "500");
        new GoalExtension().init(fake);
        fake.slashCommand("loop").handler().accept("do stuff");
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("/500"));
    }

    @Test
    void loopStopsAtTimeLimit() throws InterruptedException {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("loop").handler().accept("tick --max-time 1s");
        Thread.sleep(1200);
        fake.fireTurnEnd(1);
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("status: time-limited"));
    }

    @Test
    void parseDurationHandlesUnitsAndBareSeconds() {
        assertEquals(90_000, GoalExtension.parseDuration("90s", -1));
        assertEquals(1_800_000, GoalExtension.parseDuration("30m", -1));
        assertEquals(7_200_000, GoalExtension.parseDuration("2h", -1));
        assertEquals(5_400_000, GoalExtension.parseDuration("1h30m", -1));
        assertEquals(45_000, GoalExtension.parseDuration("45", -1)); // bare number = seconds
        assertEquals(-1, GoalExtension.parseDuration("abc", -1)); // invalid → fallback
        assertEquals(-1, GoalExtension.parseDuration("10x", -1)); // bad unit → fallback
    }

    @Test
    void goalContinueFlagMakesItAutonomousLikeLoop() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("ship it --continue");
        fake.fireTurnEnd(1);
        assertTrue(fake.sentUserMessages().stream().anyMatch(m -> m.contains("FOLLOW_UP")),
                                        "/goal --continue must auto-continue: " + fake.sentUserMessages());
    }

    @Test
    void loopNoContinueFlagMakesItASinglePass() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("loop").handler().accept("just once --no-continue");
        fake.fireTurnEnd(1);
        assertTrue(fake.sentUserMessages().isEmpty(),
                                        "/loop --no-continue must not auto-continue: " + fake.sentUserMessages());
    }

    @Test
    void goalParsesTheSameLimitFlagsAsLoop() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("x --max-time 30m --max-tokens 100 --max-iterations 5");
        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("usage: 0/100"), get.content());
        assertTrue(get.content().contains("iterations: 0/5"), get.content());
        assertTrue(get.content().contains("time left:"), get.content());
    }

    @Test
    void stopSubverbWorksOnGoalToo() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("track me");
        fake.slashCommand("goal").handler().accept("stop"); // stop now works on /goal, not just /loop
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("status: idle"));
    }

    @Test
    void plainGoalHasNoCapsWhileLoopGetsTheConfigDefault() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("track only");
        assertFalse(execute(fake.tool("get_goal"), Map.of()).content().contains("iterations:"),
                                        "plain /goal (no --continue) has no iteration cap");

        fake.slashCommand("goal").handler().accept("clear");
        fake.slashCommand("loop").handler().accept("auto");
        assertTrue(execute(fake.tool("get_goal"), Map.of()).content().contains("iterations: 0/25"),
                                        "/loop applies the default iteration cap");
    }

    private static Tool.ToolResult execute(Tool.ToolDefinition tool, Map<String, Object> args) {
        return tool.execute().execute("id", new Tool.Args(args), ".").join();
    }

    @Test
    void statusLabelMapsAllStatuses() {
        assertEquals("active", GoalExtension.statusLabel(GoalState.Status.ACTIVE));
        assertEquals("paused", GoalExtension.statusLabel(GoalState.Status.PAUSED));
        assertEquals("complete", GoalExtension.statusLabel(GoalState.Status.COMPLETE));
        assertEquals("budget-limited", GoalExtension.statusLabel(GoalState.Status.BUDGET_LIMITED));
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

    @Test
    void parseObjectiveTrimsWhitespace() {
        var result = GoalExtension.parseObjective("  goal  |  criterion  ");
        assertEquals("goal", result[0]);
        assertEquals("criterion", result[1]);
    }

    @Test
    void clearCommandResetsToIdleWithoutNpe() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        var goalCmd = fake.slashCommand("goal");

        goalCmd.handler().accept("write the docs"); // create an active goal
        // clearing transitions to IDLE → the snapshot omits json; must not NPE (Map.copyOf once rejected a null value)
        assertDoesNotThrow(() -> goalCmd.handler().accept("clear"));

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().toLowerCase().contains("idle"), get.content());
    }
}
