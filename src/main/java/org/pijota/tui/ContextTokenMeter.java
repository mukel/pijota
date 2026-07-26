package org.pijota.tui;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Caches the (relatively expensive) estimated context-token count for the status line. The estimate is recomputed only
 * when the message count changes or the cached value is older than a short TTL, so redraws stay cheap. Pure and
 * unit-testable: the message count and the estimator are injected.
 */
final class ContextTokenMeter {

    private final IntSupplier messageCount;
    private final LongSupplier estimatedTokens;
    private final long ttlMs;

    private volatile long cached = -1;
    private volatile long cachedAt;
    private volatile int cachedMessageCount = -1;

    ContextTokenMeter(IntSupplier messageCount, LongSupplier estimatedTokens, long ttlMs) {
        this.messageCount = messageCount;
        this.estimatedTokens = estimatedTokens;
        this.ttlMs = ttlMs;
    }

    /** The estimated context-token count, reusing the cached value while the transcript and TTL are unchanged. */
    long tokens() {
        return tokens(System.currentTimeMillis());
    }

    // Visible for testing: deterministic clock.
    long tokens(long now) {
        int messages = messageCount.getAsInt();
        long value = cached;
        if (value >= 0 && cachedMessageCount == messages && now - cachedAt < ttlMs)
            return value;
        value = estimatedTokens.getAsLong();
        cached = value;
        cachedAt = now;
        cachedMessageCount = messages;
        return value;
    }
}
