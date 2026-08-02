package org.pijota.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.config.ProviderCatalog;
import org.pijota.messages.Message;
import org.pijota.testkit.WireFixture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The third wire family cannot be replayed yet, and this pins exactly why rather than leaving the gap unrecorded.
 *
 * <p>
 * {@code ProviderFactory.GoogleProvider.create} accepts a {@code baseUrl} and never passes it to the builder, so every
 * Gemini request goes to Google no matter what is configured. That is not only a testing problem: a user pointing a
 * Gemini-compatible gateway or proxy at pijota is silently ignored, which is worse than an error because the traffic
 * still goes somewhere, just not where they asked.
 *
 * <p>
 * It is not a one-line fix. langchain4j defaults this family to {@code …/v1beta} while pijota's catalog records
 * {@code …googleapis.com} with no version, so passing the catalog value straight through would drop the version segment
 * and break Gemini for everyone. Closing it means deciding where the version segment lives.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GeminiWireReplayTest {

    private WireFixture wire;

    @BeforeEach
    void startFixture() throws Exception {
        wire = WireFixture.start();
    }

    @AfterEach
    void stopFixture() {
        if (wire != null)
            wire.close();
    }

    /**
     * Documents the gap as a behaviour: a custom base URL is accepted and discarded. When this starts failing, the
     * override works and the rest of this class can become real replay coverage.
     */
    @Test
    void aCustomBaseUrlIsCurrentlyIgnoredForThisFamily() throws Exception {
        var llm = new LlmService("google", "gemini-2.5-flash", p -> "test-key", wire.baseUrl(), List.of(), null);

        // No fixture response is configured: if the request ever arrives here, the override started working.
        try {
            llm.streamMessages(List.of(new Message.User(UUID.randomUUID(), Instant.now(), "hello", List.of())),
                                            List.of(), (a, b) -> {}).get(15, TimeUnit.SECONDS);
        } catch (Exception expected) {
            // Reaching the real endpoint without a valid key fails; that failure is the symptom, not the assertion.
        }

        assertTrue(wire.requests().isEmpty(), "the custom base URL now reaches the fixture: GoogleProvider honours "
                                        + "baseUrl, so this test should be replaced with real replay coverage");
    }

    /**
     * The two halves of the fix, recorded so whoever closes it does not rediscover them: the builder does support a
     * base URL, and the two defaults disagree about the version segment.
     */
    @Test
    void theBuilderSupportsABaseUrlAndTheDefaultsDisagreeAboutTheVersionSegment() throws Exception {
        var builderType = Class.forName("dev.langchain4j.model.googleai.BaseGeminiChatModel$"
                                        + "GoogleAiGeminiChatModelBaseBuilder");
        assertDoesNotThrow(() -> builderType.getMethod("baseUrl", String.class),
                                        "the builder does expose baseUrl; GoogleProvider simply does not call it");

        String catalog = ProviderCatalog.baseUrl("google");
        assertNotNull(catalog, "the catalog records a base URL that is currently unused");
        assertFalse(catalog.contains("v1beta"), "the catalog value carries no version segment, while langchain4j's "
                                        + "default is .../v1beta -- passing the catalog value through unchanged would "
                                        + "break every Gemini request: " + catalog);
    }
}
