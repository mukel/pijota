package org.pijota.llm;

import org.pijota.util.Env;

import java.time.Duration;

/**
 * LLM network timeouts, tunable without a rebuild. Raise the request timeout for very slow endpoints, lower it to fail
 * (and retry) faster on flaky networks. Each reads a {@code pijota.*} system property first, then a {@code PIJOTA_*}
 * env var, else the default (matching the convention in {@link org.pijota.util.PijotaHome}). Values are whole seconds;
 * a missing, blank, non-numeric, or non-positive value falls back to the default.
 *
 * <ul>
 * <li>{@code -Dpijota.llm.request-timeout=SECONDS} / {@code PIJOTA_LLM_REQUEST_TIMEOUT} (default 300): how long a
 * request/stream may take before failing with a retryable timeout; applied to every provider and to LlmService's stream
 * safety-net.</li>
 * <li>{@code -Dpijota.llm.first-token-timeout=SECONDS} / {@code PIJOTA_LLM_FIRST_TOKEN_TIMEOUT} (default 60): how long
 * a request may produce nothing at all before failing with a retryable timeout. A stalled prefill is a different
 * failure from a slow stream: nothing has arrived, so nothing is lost by asking again on a fresh connection, and
 * waiting the full request timeout for it means a stall costs minutes per attempt instead of one.</li>
 * <li>{@code -Dpijota.llm.connect-timeout=SECONDS} / {@code PIJOTA_LLM_CONNECT_TIMEOUT} (default 10): how long to wait
 * for a TCP connection before giving up.</li>
 * </ul>
 */
public final class LlmTimeouts {

    private LlmTimeouts() {}

    /** Request/stream timeout, applied to every provider builder and LlmService's stream safety-net. */
    public static Duration request() {
        return Duration.ofSeconds(Env.seconds("pijota.llm.request-timeout", "PIJOTA_LLM_REQUEST_TIMEOUT", 300));
    }

    /**
     * How long a request may produce nothing before it is abandoned and retried.
     *
     * <p>
     * Shorter than {@link #request()} on purpose: that one bounds a stream that is arriving, this one bounds silence.
     * Kept below the request timeout, since a first-token deadline longer than the total would never fire.
     */
    public static Duration firstToken() {
        long seconds = Env.seconds("pijota.llm.first-token-timeout", "PIJOTA_LLM_FIRST_TOKEN_TIMEOUT", 60);
        return Duration.ofSeconds(Math.min(seconds, request().toSeconds()));
    }

    /** TCP connect timeout for the hand-rolled clients. */
    public static Duration connect() {
        return Duration.ofSeconds(Env.seconds("pijota.llm.connect-timeout", "PIJOTA_LLM_CONNECT_TIMEOUT", 10));
    }
}
