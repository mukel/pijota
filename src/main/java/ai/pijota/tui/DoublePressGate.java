package ai.pijota.tui;

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

    /**
     * Returns true if triggered (second press within window). Returns false and arms the gate on first press or if
     * expired.
     */
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
