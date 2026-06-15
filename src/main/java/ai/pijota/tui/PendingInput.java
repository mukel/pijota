package ai.pijota.tui;

import java.util.concurrent.CompletableFuture;

/**
 * A single in-flight request for user input. The agent/tool thread {@link #open}s a request and awaits the returned
 * future; the UI thread later {@link #fulfill}s or {@link #cancel}s it. The backing future reference is
 * {@code volatile} so the UI thread always observes a request opened on another thread — getting this wrong silently
 * drops the answer and deadlocks the waiting agent. Centralising the lifecycle here makes that correct by construction
 * for every dialog kind.
 */
final class PendingInput<T> {

    private volatile CompletableFuture<T> future;

    /**
     * Opens a new request, replacing any previous one. The returned future is settled by
     * {@link #fulfill}/{@link #cancel}.
     */
    CompletableFuture<T> open() {
        var f = new CompletableFuture<T>();
        future = f;
        return f;
    }

    /** Whether a request is currently awaiting a response. */
    boolean active() {
        var f = future;
        return f != null && !f.isDone();
    }

    /**
     * Completes the active request with {@code value}; returns false (no-op) if none is active. The future is settled
     * on the common pool, never the caller's thread: the caller is the UI input thread, and the waiter is the agent
     * loop, which would otherwise resume its next model turn <em>on</em> the UI thread and freeze the session.
     */
    boolean fulfill(T value) {
        var f = future;
        if (f == null || f.isDone())
            return false;
        future = null;
        f.completeAsync(() -> value);
        return true;
    }

    /** Cancels the active request (off the caller's thread, see {@link #fulfill}); returns false if none is active. */
    boolean cancel() {
        var f = future;
        if (f == null || f.isDone())
            return false;
        future = null;
        CompletableFuture.runAsync(() -> f.cancel(true));
        return true;
    }
}
