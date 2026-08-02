package org.pijota.llm;

import org.pijota.config.ProviderCatalog;
import org.pijota.messages.Message;
import org.pijota.testkit.WireFixture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The third wire family cannot be replayed, and this records why rather than leaving a silent hole in the coverage.
 *
 * <p>
 * {@code ProviderFactory.GoogleProvider.create} accepts a {@code baseUrl} and never passes it on, so Gemini requests
 * always go to Google. That is deliberate: Google does not license these models to be served anywhere else, so there is
 * no endpoint to redirect to. A Gemini-compatible gateway that did exist would almost certainly speak the
 * OpenAI-compatible dialect, which pijota reaches through a different provider family entirely.
 *
 * <p>
 * The consequence for testing is the part worth knowing: with no way to point this family at a fixture, its request
 * building and response parsing are covered only by {@code make native-smoke}, which needs a real key. If that ever
 * changes, langchain4j defaults the family to {@code …/v1beta} while pijota's catalog records {@code
 * …googleapis.com} with no version segment, so the two would have to be reconciled before a base URL could be honoured.
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
     * Pins the behaviour so it stays a decision rather than becoming an accident: the base URL is accepted and
     * discarded. If this ever starts failing, someone has wired it up and this family can gain real replay coverage.
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
                                        + "baseUrl, so this family can now be replayed like the other two");
    }

    /**
     * The two facts anyone revisiting this would otherwise rediscover: the builder does support a base URL, and the two
     * defaults disagree about the version segment.
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
