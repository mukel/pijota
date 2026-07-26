package org.pijota.testkit;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Polls a condition until it holds or a deadline elapses, failing the test on timeout. Replaces the hand-rolled
 * {@code while (deadline) { Thread.sleep(10) }} loops duplicated across async tests.
 */
public final class Await {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private Await() {}

    public static void until(BooleanSupplier condition) {
        until(condition, DEFAULT_TIMEOUT, "condition not met within " + DEFAULT_TIMEOUT.toMillis() + "ms");
    }

    public static void until(BooleanSupplier condition, Duration timeout, String message) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError(message);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
