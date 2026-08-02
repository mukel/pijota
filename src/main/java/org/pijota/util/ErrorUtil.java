package org.pijota.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class ErrorUtil {
    private ErrorUtil() {}

    public static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                                        && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final int MAX_MESSAGE_LEN = 600;

    public static String message(Throwable t) {
        Throwable unwrapped = unwrap(t);
        String message = unwrapped.getMessage();
        if (message == null || message.isBlank())
            message = describe(unwrapped);
        return truncate(sanitize(concise(message)));
    }

    /**
     * Human-readable fallback for exceptions that arrive with no message. A bare class name like "ConnectException"
     * tells a user nothing. Common network failures (the model endpoint unreachable / slow / misnamed) get actionable
     * text; anything else degrades to the class's simple name.
     */
    private static final String NO_RESPONSE_IN_TIME = "the model server didn't respond in time (timeout)";

    private static String describe(Throwable t) {
        return switch (t) {
            case ConnectException e ->
                "couldn't reach the model server (connection refused). Is it running and is the base URL correct?";
            case UnknownHostException e -> "unknown host \"" + e.getMessage() + "\". Check the provider's base URL";
            case HttpTimeoutException e -> NO_RESPONSE_IN_TIME;
            case SocketTimeoutException e -> NO_RESPONSE_IN_TIME;
            case TimeoutException e -> "the model stream stalled, no response in time (timeout)";
            case NoRouteToHostException e -> "no route to the model server. Check the host/port and network";
            default -> t.getClass().getSimpleName();
        };
    }

    /**
     * Provider errors frequently arrive as a JSON blob, sometimes echoing the entire request (system prompt and all).
     * Pull out the human-readable part ({@code error.message}, plus {@code error.code} when present) so the terminal
     * shows one short line instead of a screenful. Returns the input unchanged when it isn't recognizable error JSON.
     */
    static String concise(String raw) {
        if (raw == null)
            return "";
        String msg = raw.strip();
        int brace = msg.indexOf('{');
        if (brace < 0)
            return msg;
        try {
            var root = Util.MAPPER.readTree(msg.substring(brace));
            var err = root.findValue("error"); // first "error" node at any depth (often nested under "response")
            if (err != null && err.isTextual() && !err.asText().isBlank())
                return err.asText();
            var node = err != null && err.isObject() ? err : root;
            String m = node.path("message").asText(null);
            if (m != null && !m.isBlank()) {
                String code = node.path("code").asText(null);
                return code != null && !code.isBlank() ? m + " (" + code + ")" : m;
            }
        } catch (Exception ignored) {
            // not JSON we understand, fall back to the raw text (the caller truncates it)
        }
        return msg;
    }

    static String truncate(String s) {
        if (s == null)
            return "";
        return s.length() <= MAX_MESSAGE_LEN ? s : s.substring(0, MAX_MESSAGE_LEN) + "… (truncated)";
    }

    public static String sanitize(String message) {
        if (message == null)
            return "";
        return message.replaceAll("sk-[A-Za-z0-9_-]{6,}", "sk-***").replaceAll("sk-ant-[A-Za-z0-9_-]{6,}", "sk-ant-***")
                                        .replaceAll("(?i)(api[_ -]?key[^:]*: )[^\\s,}]+", "$1***")
                                        .replaceAll("(?i)(authorization: bearer )[^\\s,}]+", "$1***");
    }

    /** The full stack trace of {@code t} as a string (for logs and error transcripts). */
    public static String stackTrace(Throwable t) {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        t.printStackTrace(pw);
        return sw.toString();
    }

    public static boolean isContextOverflow(Throwable t) {
        // Check the RAW message, not the conciseified one. The overflow hint may live outside error.message.
        String m = t != null ? unwrap(t).getMessage() : null;
        if (m == null)
            return false;
        String lower = m.toLowerCase();
        return lower.contains("context_length_exceeded") || lower.contains("maximum context length")
                                        || lower.contains("prompt is too long") || lower.contains("reduce the length")
                                        || lower.contains("too long");
    }

    private static boolean hasStatusCode(String msg, int... codes) {
        for (int code : codes) {
            String s = String.valueOf(code);
            int idx = msg.indexOf(s);
            if (idx < 0)
                continue;
            boolean startBoundary = idx == 0 || !Character.isDigit(msg.charAt(idx - 1));
            int after = idx + s.length();
            boolean endBoundary = after >= msg.length() || !Character.isDigit(msg.charAt(after));
            if (startBoundary && endBoundary)
                return true;
        }
        return false;
    }

    // Hard quota/billing/subscription-cap failures: a 429 (or gateway error) that will NOT clear by retrying. The
    // account is out of credits or over a plan limit. Checked BEFORE the retryable patterns so it surfaces immediately
    // instead of burning the whole retry budget on backoff.
    // NB: bare "quota exceeded" is deliberately NOT here. Gemini/Vertex RESOURCE_EXHAUSTED uses it for per-minute rate
    // quotas that DO clear on retry (see isRetryableDetectsTransientErrors). Only unambiguously-hard billing/plan-cap
    // wording goes here.
    private static final List<String> NON_RETRYABLE = List.of("insufficient_quota", "out of budget", "billing",
                                    "monthly usage limit reached", "available balance", "gousagelimiterror",
                                    "freeusagelimiterror",
                                    // bad credentials never heal on retry; providers word them without a status code
                                    // (OpenAI "incorrect api key", Anthropic "authentication_error", Gemini "api key
                                    // not valid"), so name them or a dead key silently backs off for minutes
                                    "invalid_api_key", "incorrect api key", "invalid api key", "api key not valid",
                                    "authentication_error", "unauthorized");

    // Transient failures worth retrying: throttles, 5xx, transport drops, and premature stream endings that typically
    // clear on a fresh request. Substrings are matched against the lower-cased message.
    private static final List<String> RETRYABLE = List.of(
                                    // throttling / capacity
                                    "rate limit", "rate_limit", "ratelimit", "too many requests", "429", "overloaded",
                                    "capacity", "resource_exhausted", "resource exhausted",
                                    // 5xx / server-side transient
                                    "server error", "internal error", "temporarily unavailable", "service unavailable",
                                    "provider returned error", "500", "502", "503", "504", "524",
                                    // network / transport drops
                                    "timeout", "timed out", "connection reset", "connection refused", "connection lost",
                                    "network error", "other side closed", "fetch failed", "upstream connect",
                                    "reset before headers", "socket hang up",
                                    // premature stream endings + explicit mid-stream retry guidance
                                    "ended without", "stream ended before message_stop",
                                    "stream ended before a terminal response event",
                                    "http2 request did not get a response", "retry delay", "you can retry your request",
                                    "try your request again", "please retry your request");

    public static boolean isRetryable(Throwable ex) {
        if (ex == null)
            return false;
        Throwable unwrapped = unwrap(ex);
        String msg = unwrapped.getMessage() != null ? unwrapped.getMessage().toLowerCase() : "";

        if (hasStatusCode(msg, 400, 401, 403, 404, 413))
            return false;
        if (isContextOverflow(unwrapped))
            return false;
        if (containsAny(msg, NON_RETRYABLE)) // hard quota/billing, retrying won't help, fail fast
            return false;

        return containsAny(msg, RETRYABLE) || unwrapped instanceof IOException || unwrapped instanceof TimeoutException;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles)
            if (haystack.contains(needle))
                return true;
        return false;
    }

    /** Default cap for backoff waits, so a long server-requested or computed delay can't stall a turn/loop forever. */
    public static final long MAX_BACKOFF_MS = 60_000L;

    /** Exponential backoff in ms: {@code baseMs * 2^attempt} (0-based), capped at {@code capMs}. */
    public static long backoffMs(long baseMs, int attempt, long capMs) {
        if (attempt <= 0)
            return Math.min(baseMs, capMs);
        if (attempt >= 62) // the cap dominates long before this; guard the shift from overflow
            return capMs;
        return Math.min(baseMs << attempt, capMs);
    }

}
