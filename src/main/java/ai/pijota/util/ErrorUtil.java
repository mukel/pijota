package ai.pijota.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

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
}
