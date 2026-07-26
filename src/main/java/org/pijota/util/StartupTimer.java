package org.pijota.util;

import java.util.concurrent.atomic.AtomicLong;

/** Lightweight opt-in startup phase profiler controlled by {@code PIJOTA_STARTUP_TIMING}. */
public final class StartupTimer {
    private static final boolean ENABLED = System.getenv("PIJOTA_STARTUP_TIMING") != null;
    private static final long START_NANOS = System.nanoTime();
    private static final AtomicLong LAST_NANOS = new AtomicLong(START_NANOS);

    private StartupTimer() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void mark(String phase) {
        if (!ENABLED)
            return;
        long now = System.nanoTime();
        long previous = LAST_NANOS.getAndSet(now);
        long totalMs = (now - START_NANOS) / 1_000_000;
        long deltaMs = (now - previous) / 1_000_000;
        System.err.println("[startup] " + phase + ": " + totalMs + " ms (+" + deltaMs + " ms)");
    }
}
