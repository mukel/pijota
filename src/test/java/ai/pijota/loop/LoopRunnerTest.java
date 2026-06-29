package ai.pijota.loop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static ai.pijota.loop.LoopTestSupport.opts;
import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for the loop core — no git, no LLM, all collaborators faked. */
class LoopRunnerTest {

    private static final LoopRunner.Git NO_GIT = new LoopRunner.NoGit();

    /** Records the prompts it was asked to run; always succeeds. */
    private static final class RecordingStep implements LoopRunner.Step {
        final List<String> prompts = new ArrayList<>();

        @Override
        public String run(String prompt) {
            prompts.add(prompt);
            return "did: " + prompt;
        }
    }

    @Test
    void stopsAtMaxIterations() {
        var step = new RecordingStep();
        var s = LoopRunner.run("obj", opts(3, 0, false), step, () -> 0, () -> 0, NO_GIT, () -> false, m -> {});
        assertEquals(3, s.iterations());
        assertEquals(3, step.prompts.size());
        assertTrue(s.stopReason().startsWith("max-iterations"), s.stopReason());
    }

    @Test
    void stopsWhenCumulativeTokensReachBudget() {
        var tokens = new AtomicLong();
        // each iteration "spends" 100 tokens; budget 250 → stop once total ≥ 250 (after 3 iterations: 300)
        LoopRunner.Step step = p -> {
            tokens.addAndGet(100);
            return "x";
        };
        var s = LoopRunner.run("obj", opts(0, 250, false), step, tokens::get, () -> 0, NO_GIT, () -> false, m -> {});
        assertEquals(3, s.iterations());
        assertTrue(s.stopReason().startsWith("max-tokens"), s.stopReason());
        assertEquals(300, s.tokens());
    }

    @Test
    void cleanTreeAfterCommitIterationIsNaturalDone() {
        var git = new FakeGit();
        git.dirty = false; // the iteration produced no changes
        var s = LoopRunner.run("obj", opts(0, 0, true), p -> "x", () -> 0, () -> 0, git, () -> false, m -> {});
        assertEquals(1, s.iterations());
        assertEquals(0, s.commits());
        assertEquals("done (no changes)", s.stopReason());
    }

    @Test
    void committingIterationsCountAndCommit() {
        var git = new FakeGit();
        git.dirty = true; // every iteration leaves changes to commit
        var s = LoopRunner.run("obj", opts(2, 0, true), p -> "summary line", () -> 0, () -> 0, git, () -> false,
                                        m -> {});
        assertEquals(2, s.iterations());
        assertEquals(2, s.commits());
        assertEquals(2, git.commitMessages.size());
        assertTrue(git.commitMessages.get(0).startsWith("pijota loop: iteration 1 — summary line"));
    }

    @Test
    void failuresRollBackAndThreeConsecutiveStop() {
        var git = new FakeGit();
        git.dirty = true;
        LoopRunner.Step alwaysFails = p -> {
            throw new RuntimeException("boom");
        };
        var s = LoopRunner.run("obj", opts(0, 0, true), alwaysFails, () -> 0, () -> 0, git, () -> false, m -> {});
        assertEquals(0, s.iterations());
        assertEquals(3, git.rollbacks);
        assertEquals("too many consecutive failures", s.stopReason());
    }

    @Test
    void aSuccessResetsTheFailureCounter() {
        var git = new FakeGit();
        git.dirty = true;
        var failThenSucceed = new LoopRunner.Step() {
            int call = 0;

            @Override
            public String run(String prompt) {
                call++;
                if (call == 1 || call == 2)
                    throw new RuntimeException("boom");
                return "ok"; // 3rd call succeeds → counter resets, loop continues to maxIter
            }
        };
        var s = LoopRunner.run("obj", opts(1, 0, true), failThenSucceed, () -> 0, () -> 0, git, () -> false, m -> {});
        assertEquals(1, s.iterations());
        assertEquals(1, s.commits());
        assertEquals(2, git.rollbacks); // two failures rolled back, then a success
        assertTrue(s.stopReason().startsWith("max-iterations"), s.stopReason());
    }

    @Test
    void continuationPromptReassertsObjectiveAfterIterationZero() {
        var step = new RecordingStep();
        LoopRunner.run("ship it", opts(3, 0, false), step, () -> 0, () -> 0, NO_GIT, () -> false, m -> {});
        assertEquals("ship it", step.prompts.get(0));
        assertEquals("Continue toward the objective: ship it", step.prompts.get(1));
        assertEquals("Continue toward the objective: ship it", step.prompts.get(2));
    }

    @Test
    void interruptedStopsBeforeRunning() {
        var step = new RecordingStep();
        var s = LoopRunner.run("obj", opts(5, 0, false), step, () -> 0, () -> 0, NO_GIT, () -> true, m -> {});
        assertEquals(0, s.iterations());
        assertTrue(step.prompts.isEmpty());
        assertEquals("interrupted", s.stopReason());
    }

    @Test
    void interruptDuringAnIterationStopsCleanlyNotAsFailure() {
        var git = new FakeGit();
        git.dirty = true;
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean(false);
        // Ctrl-C lands mid-iteration: the step throws (as an aborted prompt would) with the flag now set.
        LoopRunner.Step abortedStep = p -> {
            interrupted.set(true);
            throw new RuntimeException("aborted");
        };
        var s = LoopRunner.run("obj", opts(0, 0, true), abortedStep, () -> 0, () -> 0, git, interrupted::get, m -> {});
        assertEquals(0, s.iterations());
        assertEquals("interrupted", s.stopReason());
        assertEquals(0, git.rollbacks, "an interrupt is not an iteration failure → no rollback");
    }

    @Test
    void oneLineTakesFirstNonBlankAndCaps() {
        assertEquals("(no summary)", LoopRunner.oneLine(null));
        assertEquals("(no summary)", LoopRunner.oneLine("   \n  "));
        assertEquals("first", LoopRunner.oneLine("\n\n  first\nsecond"));
        assertEquals(72, LoopRunner.oneLine("x".repeat(200)).length());
    }

    /** In-memory {@link LoopRunner.Git} that records calls. */
    private static final class FakeGit implements LoopRunner.Git {
        boolean dirty = true;
        int rollbacks = 0;
        final List<String> commitMessages = new ArrayList<>();

        @Override
        public boolean isDirty() {
            return dirty;
        }

        @Override
        public String commit(String message) {
            commitMessages.add(message);
            return "abc123";
        }

        @Override
        public void rollback() {
            rollbacks++;
        }

        @Override
        public void push() {}
    }
}
