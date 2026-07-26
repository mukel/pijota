package org.pijota.tui;

/**
 * A transient status message shown in place of the default prompt status, either sticky (until replaced or cleared) or
 * timed (clears itself after a duration). Reads lazily drop an expired message so callers never observe a stale one.
 */
final class StatusOverride {

    private volatile String message;
    private volatile long expiresAt;

    /** Sets a status that stays until replaced or cleared. */
    void set(String message) {
        this.message = message;
        this.expiresAt = 0;
    }

    /** Sets a status that clears itself after {@code durationMs} milliseconds. */
    void setTransient(String message, long durationMs) {
        this.message = message;
        this.expiresAt = System.currentTimeMillis() + Math.max(1, durationMs);
    }

    void clear() {
        this.message = null;
        this.expiresAt = 0;
    }

    /** The active message, or {@code null} if none or expired. Clears an expired message as a side effect. */
    String current() {
        if (message == null)
            return null;
        if (expired()) {
            clear();
            return null;
        }
        return message;
    }

    /** True if a timed message has just elapsed (and clears it); used to drive a redraw on expiry. */
    boolean expireIfDue() {
        if (message != null && expired()) {
            clear();
            return true;
        }
        return false;
    }

    private boolean expired() {
        long at = expiresAt;
        return at > 0 && System.currentTimeMillis() >= at;
    }
}
