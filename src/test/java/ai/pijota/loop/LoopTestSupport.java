package ai.pijota.loop;

/** Shared helpers for the loop tests. */
final class LoopTestSupport {

    private LoopTestSupport() {}

    /** A minimal {@link LoopRunner.Options} with no push and no backoff — the common shape across the loop tests. */
    static LoopRunner.Options opts(int maxIter, long maxTokens, boolean commit) {
        return new LoopRunner.Options(maxIter, maxTokens, commit, false, 0);
    }
}
