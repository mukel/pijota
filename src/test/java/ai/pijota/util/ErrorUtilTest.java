package ai.pijota.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorUtilTest {
    @Test
    void unwrapPeelsCompletionAndExecutionExceptions() {
        var cause = new RuntimeException("real");
        var wrapped = new CompletionException(new ExecutionException(cause));

        assertEquals(cause, ErrorUtil.unwrap(wrapped));
    }

    @Test
    void messageStripsApiKeysAndSecrets() {
        var t = new RuntimeException("api key: sk-abc123def456 secret info");

        var msg = ErrorUtil.message(t);
        assertFalse(msg.contains("abc123"));
        assertTrue(msg.contains("***"));
    }

    @Test
    void messageSanitizesAuthorizationHeaders() {
        var t = new RuntimeException("Authorization: Bearer token-secret-xyz response");

        var msg = ErrorUtil.message(t);
        assertFalse(msg.contains("token-secret"));
        assertTrue(msg.contains("***"));
    }

    @Test
    void isContextOverflowDetectsKnownPatterns() {
        assertTrue(ErrorUtil.isContextOverflow(new RuntimeException("context_length_exceeded")));
        assertTrue(ErrorUtil.isContextOverflow(new RuntimeException("maximum context length")));
        assertTrue(ErrorUtil.isContextOverflow(new RuntimeException("prompt is too long")));
    }

    @Test
    void isContextOverflowReturnsFalseForRegularErrors() {
        assertFalse(ErrorUtil.isContextOverflow(new RuntimeException("network timeout")));
        assertFalse(ErrorUtil.isContextOverflow(null));
    }

    @Test
    void isRetryableDetectsTransientErrors() {
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("rate limit exceeded")));
        assertTrue(ErrorUtil.isRetryable(new IOException("connection reset")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("503 server error")));
    }

    @Test
    void isRetryableRejectsAuthAndContextOverflowErrors() {
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("401 unauthorized")));
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("context_length_exceeded")));
        assertFalse(ErrorUtil.isRetryable(null));
    }

    @Test
    void retryAfterDelayParsesIntegerSeconds() {
        var ex = new RuntimeException("retry later") {
            public Object response() {
                return this;
            }

            public Object headers() {
                return this;
            }

            public String get(String name) {
                return "30";
            }
        };

        assertEquals(30_000, ErrorUtil.retryAfterDelayMs(ex, 5000));
    }

    @Test
    void retryAfterDelayFallsBackToDefaultForNullException() {
        assertEquals(5000, ErrorUtil.retryAfterDelayMs(null, 5000));
    }
}
