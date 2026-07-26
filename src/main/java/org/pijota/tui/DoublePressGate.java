package org.pijota.tui;

/**
 * Simple double-press gate: first invocation arms the gate and returns false; second invocation within the window
 * returns true and disarms.
 */
final class DoublePressGate {
    private final long windowMs;
    private long armedUntil;

    DoublePressGate(long windowMs) {
        this.windowMs = windowMs;
    }

    /** True on the second press within the window; otherwise arms the gate and returns false. */
    boolean tryFire() {
        long now = System.currentTimeMillis();
        if (armedUntil > 0 && now <= armedUntil) {
            armedUntil = 0;
            return true;
        }
        armedUntil = now + windowMs;
        return false;
    }

    void disarm() {
        armedUntil = 0;
    }
}
