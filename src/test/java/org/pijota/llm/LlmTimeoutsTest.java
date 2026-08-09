package org.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The LLM timeout knobs expose positive durations and honor their system-property overrides. */
class LlmTimeoutsTest {

    @Test
    void defaultsArePositiveDurations() {
        assertTrue(LlmTimeouts.request().toSeconds() > 0);
        assertTrue(LlmTimeouts.connect().toSeconds() > 0);
    }

    @Test
    void requestTimeoutHonorsTheSystemProperty() {
        System.setProperty("pijota.llm.request-timeout", "90");
        try {
            assertEquals(90, LlmTimeouts.request().toSeconds());
        } finally {
            System.clearProperty("pijota.llm.request-timeout");
        }
    }

    @Test
    void firstTokenDefaultsShorterThanTheRequestTimeout() {
        assertTrue(LlmTimeouts.firstToken().toSeconds() > 0);
        assertTrue(LlmTimeouts.firstToken().toSeconds() < LlmTimeouts.request().toSeconds(),
                                        "silence must be given up on before a stream that is arriving");
    }

    @Test
    void firstTokenHonorsTheSystemProperty() {
        System.setProperty("pijota.llm.first-token-timeout", "12");
        try {
            assertEquals(12, LlmTimeouts.firstToken().toSeconds());
        } finally {
            System.clearProperty("pijota.llm.first-token-timeout");
        }
    }

    /**
     * A silence deadline longer than the total would never fire: the request timeout would settle the stream first, and
     * the knob would look like it did nothing. Clamped, so lowering the request timeout lowers this with it.
     */
    @Test
    void firstTokenNeverOutlastsTheRequestTimeout() {
        System.setProperty("pijota.llm.request-timeout", "20");
        System.setProperty("pijota.llm.first-token-timeout", "300");
        try {
            assertEquals(20, LlmTimeouts.firstToken().toSeconds());
        } finally {
            System.clearProperty("pijota.llm.request-timeout");
            System.clearProperty("pijota.llm.first-token-timeout");
        }
    }
}
