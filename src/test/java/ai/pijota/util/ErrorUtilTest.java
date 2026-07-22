package ai.pijota.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorUtilTest {

    @Test
    void messagelessNetworkErrorsBecomeActionableText() {
        // a bare "ConnectException" tells a user nothing — message() should explain it
        String connect = ErrorUtil.message(new ConnectException());
        assertFalse(connect.equals("ConnectException"), connect);
        assertTrue(connect.toLowerCase().contains("connect") || connect.toLowerCase().contains("reach"), connect);
        // unwraps wrappers too (the agent loop completes the future with a CompletionException)
        String wrapped = ErrorUtil.message(new CompletionException(new ConnectException()));
        assertEquals(connect, wrapped);
        // a timeout reads as a timeout, not the class name
        assertTrue(ErrorUtil.message(new HttpTimeoutException("")).toLowerCase().contains("time"),
                                        "timeouts should mention time");
        // the stream safety-net's bare java.util.concurrent.TimeoutException also reads as a timeout, not the class
        // name
        String stall = ErrorUtil.message(new TimeoutException());
        assertNotEquals("TimeoutException", stall, stall);
        assertTrue(stall.toLowerCase().contains("time"), stall);
    }

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
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("RESOURCE_EXHAUSTED: quota exceeded"))); // Gemini/Vertex
    }

    @Test
    void isRetryableRejectsAuthAndContextOverflowErrors() {
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("401 unauthorized")));
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("context_length_exceeded")));
        assertFalse(ErrorUtil.isRetryable(null));
    }

    @Test
    void isRetryableRejectsHardQuotaAndBillingLimits() {
        // A 429 that won't clear by retrying — surface it immediately instead of burning the retry budget.
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("429 insufficient_quota: check your billing")));
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("You are out of budget")));
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("Monthly usage limit reached")));
        assertFalse(ErrorUtil.isRetryable(new RuntimeException("enable available balance to continue")));
    }

    @Test
    void isRetryableDetectsPrematureStreamEndingsAndRetryHints() {
        // Provider-specific transient stream aborts + explicit mid-stream retry guidance.
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("Anthropic stream ended before message_stop")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("stream ended before a terminal response event")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("http2 request did not get a response")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("An error occurred. You can retry your request.")));
        assertTrue(ErrorUtil.isRetryable(new RuntimeException("504 gateway timeout")));
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
                                        .format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60));
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

    @Test
    void messageExtractsErrorFromNestedProviderJson() {
        // Codex/OpenAI "response.failed" blob that echoes the whole request — must not dump it all.
        String blob = "{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_abc\",\"status\":\"failed\","
                                        + "\"error\":{\"code\":\"server_is_overloaded\",\"message\":\"Our servers are currently "
                                        + "overloaded. Please try again later.\"},\"instructions\":\"You are pijota, a very long "
                                        + "system prompt that should never reach the terminal...\"}}";
        String msg = ErrorUtil.message(new RuntimeException(blob));
        assertEquals("Our servers are currently overloaded. Please try again later. (server_is_overloaded)", msg);
        assertFalse(msg.contains("You are pijota"), "the echoed system prompt must be stripped");
    }

    @Test
    void messageExtractsTopLevelOpenAiErrorObject() {
        String blob = "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"invalid_request_error\"}}";
        assertEquals("Invalid API key", ErrorUtil.message(new RuntimeException(blob)));
    }

    @Test
    void messageHandlesErrorAsPlainString() {
        assertEquals("boom", ErrorUtil.message(new RuntimeException("{\"error\":\"boom\"}")));
    }

    @Test
    void messageLeavesPlainTextUnchanged() {
        assertEquals("connection reset by peer", ErrorUtil.message(new RuntimeException("connection reset by peer")));
    }

    @Test
    void messageTruncatesHugeNonJsonText() {
        String huge = "x".repeat(5000);
        String msg = ErrorUtil.message(new RuntimeException(huge));
        assertTrue(msg.length() < huge.length());
        assertTrue(msg.endsWith("… (truncated)"));
    }

    @Test
    void messageStillRedactsSecretsAfterExtraction() {
        String blob = "{\"error\":{\"message\":\"bad key sk-abcdef123456 supplied\"}}";
        String msg = ErrorUtil.message(new RuntimeException(blob));
        assertTrue(msg.contains("sk-***"), msg);
        assertFalse(msg.contains("sk-abcdef123456"));
    }

    @Test
    void contextOverflowStillDetectedFromNestedJson() {
        String blob = "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"This model's maximum context "
                                        + "length is 128000 tokens.\"}}";
        assertTrue(ErrorUtil.isContextOverflow(new RuntimeException(blob)));
    }
}
