package ai.pijota.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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

    @Test
    void messageFallsBackToClassNameWhenMissing() {
        var t = new RuntimeException();
        assertEquals("RuntimeException", ErrorUtil.message(t));
    }

    @Test
    void sanitizeHandlesNull() {
        assertEquals("", ErrorUtil.sanitize(null));
    }

    @Test
    void isRetryableRecognizesIoAndTimeoutExceptions() {
        assertTrue(ErrorUtil.isRetryable(new TimeoutException("timed out")));
        assertTrue(ErrorUtil.isRetryable(new IOException("boom")));
    }

    @Test
    void isRetryableStatusCodeBoundaries() {
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("server error 1401")));
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("server error 401x")));
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("401 unauthorized")));
    }

    @Test
    void retryAfterDelayParsesHttpDate() {
        String retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME
                                        .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60));
        var ex = new RuntimeException("retry later") {
            public Object response() {
                return this;
            }

            public Object headers() {
                return this;
            }

            public String get(String name) {
                return "retry-after".equalsIgnoreCase(name) ? retryAfter : null;
            }
        };

        long delay = ErrorUtil.retryAfterDelayMs(ex, 5000);
        assertTrue(delay >= 55_000 && delay <= 65_000, "delay should be ~60s");
    }

    @Test
    void retryAfterDelayFallsBackForInvalidHeader() {
        var ex = new RuntimeException("retry later") {
            public Object response() {
                return this;
            }

            public Object headers() {
                return this;
            }

            public String get(String name) {
                return "not-a-number";
            }
        };
        assertEquals(5000, ErrorUtil.retryAfterDelayMs(ex, 5000));
    }

    @Test
    void unwrapReturnsNonWrappedException() {
        var ex = new RuntimeException("plain");
        assertEquals(ex, ErrorUtil.unwrap(ex));
    }

    @Test
    void isRetryableMatchesTransientKeywords() {
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("connection refused")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("502 bad gateway")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("503 service unavailable")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("internal error")));
    }

    @Test
    void isContextOverflowMatchesTooLong() {
        assertTrue(ErrorUtil.isContextOverflow(new RuntimeException("reduce the length")));
        assertTrue(ErrorUtil.isContextOverflow(new RuntimeException("too long")));
    }

    @Test
    void retryAfterDelayFallsBackWhenHeaderMissing() {
        var ex = new RuntimeException("retry later") {
            public Object response() {
                return null;
            }
        };
        assertEquals(5000, ErrorUtil.retryAfterDelayMs(ex, 5000));
    }
}
