package ai.pijota.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class ErrorUtil {
    private ErrorUtil() {}

    private static final List<DateTimeFormatter> HTTP_DATE_FORMATS = List.of(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z"),
        DateTimeFormatter.ofPattern("EEE, dd-MMM-yy HH:mm:ss z"),
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy")
    );

    public static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static String message(Throwable t) {
        Throwable unwrapped = unwrap(t);
        String message = unwrapped.getMessage();
        if (message == null || message.isBlank()) message = unwrapped.getClass().getSimpleName();
        return sanitize(message);
    }

    public static String sanitize(String message) {
        if (message == null) return "";
        return message
            .replaceAll("sk-[A-Za-z0-9_-]{6,}", "sk-***")
            .replaceAll("sk-ant-[A-Za-z0-9_-]{6,}", "sk-ant-***")
            .replaceAll("(?i)(api[_ -]?key[^:]*: )[^\\s,}]+", "$1***")
            .replaceAll("(?i)(authorization: bearer )[^\\s,}]+", "$1***");
    }

    public static boolean isContextOverflow(Throwable t) {
        String m = t != null ? ErrorUtil.message(t) : null;
        if (m == null) return false;
        String lower = m.toLowerCase();
        return lower.contains("context_length_exceeded")
            || lower.contains("maximum context length")
            || lower.contains("prompt is too long")
            || lower.contains("reduce the length")
            || lower.contains("too long");
    }

    public static boolean isRetryable(Throwable ex) {
        if (ex == null) return false;
        Throwable unwrapped = unwrap(ex);
        String msg = unwrapped.getMessage() != null ? unwrapped.getMessage().toLowerCase() : "";

        if (msg.contains("400") || msg.contains("401") || msg.contains("403")
            || msg.contains("404") || msg.contains("413")) return false;

        if (isContextOverflow(unwrapped)) return false;

        return msg.contains("rate limit") || msg.contains("rate_limit")
            || msg.contains("too many requests") || msg.contains("429")
            || msg.contains("overloaded") || msg.contains("capacity")
            || msg.contains("server error") || msg.contains("503")
            || msg.contains("502") || msg.contains("500")
            || msg.contains("timeout") || msg.contains("timed out")
            || msg.contains("connection reset") || msg.contains("connection refused")
            || msg.contains("temporarily unavailable")
            || msg.contains("internal error")
            || unwrapped instanceof java.io.IOException
            || unwrapped instanceof java.util.concurrent.TimeoutException;
    }

    public static long retryAfterDelayMs(Throwable ex, long defaultDelayMs) {
        if (ex == null) return defaultDelayMs;
        String retryAfter = extractRetryAfterHeader(ex);
        if (retryAfter == null) return defaultDelayMs;

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
            if (header != null) return header;
            current = current.getCause();
        }
        return null;
    }

    private static String tryGetRetryAfter(Object obj) {
        if (obj == null) return null;

        try {
            var responseObj = invokeMethod(obj, "response");
            if (responseObj != null) {
                var headersObj = invokeMethod(responseObj, "headers");
                if (headersObj != null) {
                    String header = (String) invokeMethodWithArg(headersObj, "get", "Retry-After");
                    if (header != null && !header.isBlank()) return header;
                    header = (String) invokeMethodWithArg(headersObj, "get", "retry-after");
                    if (header != null && !header.isBlank()) return header;
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
