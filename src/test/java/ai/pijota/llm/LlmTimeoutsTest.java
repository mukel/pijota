package ai.pijota.llm;

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
}
