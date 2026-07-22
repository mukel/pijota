package ai.pijota.util;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class ErrorUtil {
    private ErrorUtil() {}

    private static final List<DateTimeFormatter> HTTP_DATE_FORMATS = List.of(DateTimeFormatter.RFC_1123_DATE_TIME,
                                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z"),
                                    DateTimeFormatter.ofPattern("EEE, dd-MMM-yy HH:mm:ss z"),
                                    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy"));

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
     * Human-readable fallback for exceptions that arrive with no message — a bare class name like "ConnectException"
     * tells a user nothing. Common network failures (the model endpoint unreachable / slow / misnamed) get actionable
     * text; anything else degrades to the class's simple name.
     */
    private static final String NO_RESPONSE_IN_TIME = "the model server didn't respond in time (timeout)";

    private static String describe(Throwable t) {
        return switch (t) {
            case ConnectException e ->
                "couldn't reach the model server (connection refused) — is it running and is the base URL correct?";
            case UnknownHostException e -> "unknown host \"" + e.getMessage() + "\" — check the provider's base URL";
            case HttpTimeoutException e -> NO_RESPONSE_IN_TIME;
            case SocketTimeoutException e -> NO_RESPONSE_IN_TIME;
            case TimeoutException e -> "the model stream stalled — no response in time (timeout)";
            case NoRouteToHostException e -> "no route to the model server — check the host/port and network";
            default -> t.getClass().getSimpleName();
        };
    }

    /**
     * Provider errors frequently arrive as a JSON blob — sometimes echoing the entire request (system prompt and all).
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
            // not JSON we understand — fall back to the raw text (the caller truncates it)
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
        // Check the RAW message, not the conciseified one — the overflow hint may live outside error.message.
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
            if (msg.contains(String.valueOf(code))) {
                int idx = msg.indexOf(String.valueOf(code));
                boolean startBoundary = idx == 0 || !Character.isDigit(msg.charAt(idx - 1));
                boolean endBoundary = idx + String.valueOf(code).length() >= msg.length()
                                                || !Character.isDigit(msg.charAt(idx + String.valueOf(code).length()));
                if (startBoundary && endBoundary)
                    return true;
            }
        }
        return false;
    }

    public static boolean isRetryable(Throwable ex) {
        if (ex == null)
            return false;
        Throwable unwrapped = unwrap(ex);
        String msg = unwrapped.getMessage() != null ? unwrapped.getMessage().toLowerCase() : "";

        if (hasStatusCode(msg, 400, 401, 403, 404, 413))
            return false;

        if (isContextOverflow(unwrapped))
            return false;

        return msg.contains("rate limit") || msg.contains("rate_limit") || msg.contains("too many requests")
                                        || msg.contains("429") || msg.contains("overloaded") || msg.contains("capacity")
                                        || msg.contains("resource_exhausted") || msg.contains("resource exhausted")
                                        || msg.contains("server error") || msg.contains("503") || msg.contains("502")
                                        || msg.contains("500") || msg.contains("timeout") || msg.contains("timed out")
                                        || msg.contains("connection reset") || msg.contains("connection refused")
                                        || msg.contains("temporarily unavailable") || msg.contains("internal error")
                                        || unwrapped instanceof IOException || unwrapped instanceof TimeoutException;
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

    public static long retryAfterDelayMs(Throwable ex, long defaultDelayMs) {
        if (ex == null)
            return defaultDelayMs;
        String retryAfter = extractRetryAfterHeader(ex);
        if (retryAfter == null)
            return defaultDelayMs;

        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return seconds * 1000;
        } catch (NumberFormatException e1) {
            try {
                var instant = parseHttpDate(retryAfter.trim());
                long delay = Duration.between(Instant.now(), instant).toMillis();
                return Math.max(delay, 1000);
            } catch (DateTimeParseException e2) {
                return defaultDelayMs;
            }
        }
    }

    private static String extractRetryAfterHeader(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String header = tryGetRetryAfter(current);
            if (header != null)
                return header;
            current = current.getCause();
        }
        return null;
    }

    private static String tryGetRetryAfter(Object obj) {
        if (obj == null)
            return null;

        try {
            var responseObj = invokeMethod(obj, "response");
            if (responseObj != null) {
                var headersObj = invokeMethod(responseObj, "headers");
                if (headersObj != null) {
                    String header = (String) invokeMethodWithArg(headersObj, "get", "Retry-After");
                    if (header != null && !header.isBlank())
                        return header;
                    header = (String) invokeMethodWithArg(headersObj, "get", "retry-after");
                    if (header != null && !header.isBlank())
                        return header;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static Object invokeMethod(Object obj, String methodName) {
        try {
            var method = obj.getClass().getMethod(methodName);
            return method.invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object invokeMethodWithArg(Object obj, String methodName, String arg) {
        try {
            var method = obj.getClass().getMethod(methodName, String.class);
            return method.invoke(obj, arg);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant parseHttpDate(String value) {
        for (var fmt : HTTP_DATE_FORMATS) {
            try {
                return Instant.from(fmt.parse(value));
            } catch (DateTimeParseException ignored) {}
        }
        throw new DateTimeParseException("Unparseable HTTP date: " + value, value, 0);
    }
}
