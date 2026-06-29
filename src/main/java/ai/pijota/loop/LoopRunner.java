package ai.pijota.loop;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Drives an autonomous "keep working toward an objective" loop until a budget or a natural-done condition is hit (the
 * gnhf "good night, have fun" workflow). Pure orchestration: running a prompt, reading token usage, and git are all
 * injected, so the loop is unit-testable without a live model or repo. Iteration 0 prompts the objective; later
 * iterations re-assert it (the session itself carries the history, so no separate notes file is needed).
 */
public final class LoopRunner {

    /** Runs one iteration's prompt; returns the last assistant text (for a commit message) or throws on failure. */
    @FunctionalInterface
    public interface Step {
        String run(String prompt) throws Exception;
    }

    /** Git side effects, injected so the loop is testable. {@link NoGit} is used when {@code --commit} is off. */
    public interface Git {
        boolean isDirty() throws Exception;

        /** Commit the working tree; returns a short ref, or {@code null} when there was nothing to commit. */
        String commit(String message) throws Exception;

        void rollback() throws Exception;

        void push() throws Exception;
    }

    public record Options(int maxIterations, long maxTokens, boolean commit, boolean push, long backoffBaseMs) {
    }

    public record Summary(int iterations, long tokens, double cost, int commits, String stopReason) {
    }

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private LoopRunner() {}

    public static Summary run(String objective, Options opts, Step step, LongSupplier tokens, DoubleSupplier cost,
                                    Git git, BooleanSupplier interrupted, Consumer<String> log) {
        int iterations = 0;
        int commits = 0;
        int consecutiveFailures = 0;
        String stop = null;

        while (true) {
            // Stop checks come first so a fresh run with the budget already exhausted does nothing. (A natural
            // --stop-when
            // condition would slot in here too — deferred for now.)
            if (interrupted.getAsBoolean()) {
                stop = "interrupted";
                break;
            }
            if (opts.maxIterations() > 0 && iterations >= opts.maxIterations()) {
                stop = "max-iterations (" + opts.maxIterations() + ")";
                break;
            }
            if (opts.maxTokens() > 0 && tokens.getAsLong() >= opts.maxTokens()) {
                stop = "max-tokens (" + opts.maxTokens() + ")";
                break;
            }

            String prompt = iterations == 0 ? objective : "Continue toward the objective: " + objective;
            String lastText;
            try {
                lastText = step.run(prompt);
            } catch (Exception e) {
                consecutiveFailures++;
                log.accept("iteration failed (" + consecutiveFailures + "/" + MAX_CONSECUTIVE_FAILURES + "): "
                                                + rootMessage(e));
                if (opts.commit()) {
                    try {
                        git.rollback();
                    } catch (Exception re) {
                        log.accept("rollback failed: " + rootMessage(re));
                    }
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    stop = "too many consecutive failures";
                    break;
                }
                backoff(opts.backoffBaseMs(), consecutiveFailures);
                continue;
            }

            iterations++;
            consecutiveFailures = 0;

            if (opts.commit()) {
                try {
                    if (!git.isDirty()) {
                        log.accept("iteration " + iterations + " produced no changes — nothing left to do");
                        stop = "done (no changes)";
                        break;
                    }
                    String ref = git.commit("pijota loop: iteration " + iterations + " — " + oneLine(lastText));
                    if (ref != null) {
                        commits++;
                        if (opts.push())
                            git.push();
                        log.accept("iteration " + iterations + " · " + tokens.getAsLong() + " tok · committed " + ref);
                    }
                } catch (Exception e) {
                    // A commit/push failure keeps the work (no rollback) but counts as a failure toward the cap.
                    consecutiveFailures++;
                    log.accept("commit failed (" + consecutiveFailures + "/" + MAX_CONSECUTIVE_FAILURES + "): "
                                                    + rootMessage(e));
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        stop = "too many consecutive failures";
                        break;
                    }
                }
            } else {
                log.accept("iteration " + iterations + " · " + tokens.getAsLong() + " tok");
            }
        }
        return new Summary(iterations, tokens.getAsLong(), cost.getAsDouble(), commits, stop);
    }

    /** A {@link Git} that does nothing — used when {@code --commit} is off, so the loop never touches the repo. */
    public static final class NoGit implements Git {
        @Override
        public boolean isDirty() {
            return true; // never trigger the clean-tree "done" path when not committing
        }

        @Override
        public String commit(String message) {
            return null;
        }

        @Override
        public void rollback() {}

        @Override
        public void push() {}
    }

    private static void backoff(long baseMs, int failures) {
        if (baseMs <= 0)
            return;
        long delay = Math.min(baseMs * (1L << (failures - 1)), 60_000L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String oneLine(String text) {
        if (text == null || text.isBlank())
            return "(no summary)";
        for (String line : text.strip().split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty())
                return trimmed.length() > 72 ? trimmed.substring(0, 71) + "…" : trimmed;
        }
        return "(no summary)";
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c)
            c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
    }
}
