package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalStateTest {

    @Test
    void idleStateHasNoObjectiveAndIsTerminal() {
        var state = GoalState.idle();
        assertEquals(GoalState.Status.IDLE, state.status());
        assertTrue(state.objective().isEmpty());
        assertTrue(state.isTerminal());
        assertFalse(state.isActive());
        assertEquals(0, state.elapsedMs(1_000_000));
    }

    @Test
    void startCreatesActiveGoal() {
        var state = GoalState.start("implement feature X", 1000, "tests pass", true, 10, 20, 1000);

        assertEquals(GoalState.Status.ACTIVE, state.status());
        assertEquals("implement feature X", state.objective());
        assertEquals(1000, state.tokenBudget());
        assertEquals("tests pass", state.completionCriterion());
        assertTrue(state.continueOnIdle());
        assertEquals(10, state.inputTokensAtStart());
        assertEquals(20, state.outputTokensAtStart());
        assertEquals(1000, state.startedAtMs());
        assertTrue(state.isActive());
        assertFalse(state.isTerminal());
    }

    @Test
    void startRejectsBlankObjective() {
        assertThrows(IllegalArgumentException.class, () -> GoalState.start("", 0, "", false, 0, 0, 0));
    }

    @Test
    void startNormalizesNegativeBudgetAndTokens() {
        var state = GoalState.start("x", -5, null, false, -1, -2, 0);
        assertEquals(0, state.tokenBudget());
        assertEquals(0, state.inputTokensAtStart());
        assertEquals(0, state.outputTokensAtStart());
    }

    @Test
    void elapsedTimeIncreasesWhileActive() {
        var state = GoalState.start("x", 0, "", false, 0, 0, 1000);
        assertEquals(0, state.elapsedMs(1000));
        assertEquals(5000, state.elapsedMs(6000));
    }

    @Test
    void pauseStopsElapsedTime() {
        var state = GoalState.start("x", 0, "", false, 0, 0, 1000).pause(3000);
        assertEquals(GoalState.Status.PAUSED, state.status());
        assertEquals(2000, state.elapsedMs(10_000));
    }

    @Test
    void resumeAccumulatesPausedTime() {
        var state = GoalState.start("x", 0, "", false, 0, 0, 1000).pause(3000).resume(8000);
        assertEquals(GoalState.Status.ACTIVE, state.status());
        assertEquals(5000, state.totalPausedMs());
        assertEquals(3000, state.elapsedMs(9000));
    }

    @Test
    void completeCapturesElapsedTimeCorrectlyAfterPause() {
        var state = GoalState.start("x", 0, "", false, 0, 0, 1000).pause(3000).complete(5000);
        assertEquals(GoalState.Status.COMPLETE, state.status());
        assertEquals(2000, state.elapsedMs(5000));
    }

    @Test
    void cannotPauseIdleGoal() {
        assertThrows(IllegalStateException.class, () -> GoalState.idle().pause(1000));
    }

    @Test
    void cannotResumeActiveGoal() {
        var state = GoalState.start("x", 0, "", false, 0, 0, 0);
        assertThrows(IllegalStateException.class, () -> state.resume(1000));
    }

    @Test
    void currentUsageCalculatesFromBaselines() {
        var state = GoalState.start("x", 1000, "", false, 100, 50, 0);
        assertEquals(0, state.currentUsage(100, 50));
        assertEquals(100, state.currentUsage(150, 100));
        assertEquals(250, state.currentUsage(200, 200));
    }

    @Test
    void wouldExceedBudgetHonorsThreshold() {
        var state = GoalState.start("x", 100, "", false, 0, 0, 0);
        assertFalse(state.wouldExceedBudget(50, 40)); // 90 < 100
        assertTrue(state.wouldExceedBudget(60, 40)); // 100 >= 100
    }

    @Test
    void markBudgetLimitedOnlyFromActive() {
        var active = GoalState.start("x", 0, "", false, 0, 0, 0);
        assertEquals(GoalState.Status.BUDGET_LIMITED, active.markBudgetLimited(1000).status());
        assertEquals(GoalState.Status.COMPLETE, active.complete(1000).markBudgetLimited(1000).status());
    }

    @Test
    void addProgressAppendsEntryAndCapsSize() {
        var state = GoalState.start("x", 0, "", false, 0, 0, 1000);
        for (int i = 0; i < 25; i++)
            state = state.addProgress("step " + i, 1000 + i);
        assertEquals(20, state.progress().size());
        assertTrue(state.progress().getFirst().contains("step 5"));
        assertTrue(state.progress().getLast().contains("step 24"));
    }

    @Test
    void serializationRoundTripPreservesPauseTime() {
        var original = GoalState.start("implement X", 5000, "tests pass", true, 100, 200, 1000).pause(3000)
                                        .resume(8000);
        String json = original.toJson();
        var restored = GoalState.fromJson(json);

        assertEquals(original.objective(), restored.objective());
        assertEquals(original.status(), restored.status());
        assertEquals(original.tokenBudget(), restored.tokenBudget());
        assertEquals(original.completionCriterion(), restored.completionCriterion());
        assertEquals(original.continueOnIdle(), restored.continueOnIdle());
        assertEquals(original.inputTokensAtStart(), restored.inputTokensAtStart());
        assertEquals(original.outputTokensAtStart(), restored.outputTokensAtStart());
        assertEquals(original.startedAtMs(), restored.startedAtMs());
        assertEquals(original.totalPausedMs(), restored.totalPausedMs());
        assertEquals(original.pausedAtMs(), restored.pausedAtMs());
        assertEquals(original.elapsedMs(10_000), restored.elapsedMs(10_000));
    }

    @Test
    void fromJsonReturnsIdleForInvalidInput() {
        assertEquals(GoalState.Status.IDLE, GoalState.fromJson(null).status());
        assertEquals(GoalState.Status.IDLE, GoalState.fromJson("not json").status());
        assertEquals(GoalState.Status.IDLE, GoalState.fromJson("{\"status\":\"idle\"}").status());
    }

    @Test
    void fromJsonRestoresProgress() {
        var state = GoalState.start("x", 0, "", false, 0, 0, 0).addProgress("done", 1000);
        var restored = GoalState.fromJson(state.toJson());
        assertEquals(1, restored.progress().size());
        assertTrue(restored.progress().getFirst().contains("done"));
    }
}
