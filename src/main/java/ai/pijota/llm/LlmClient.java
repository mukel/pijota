package ai.pijota.llm;

import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public interface LlmClient extends AutoCloseable {
    String provider();

    String model();

    default String apiKey() {
        return "";
    }

    default long totalInputTokens() {
        return 0;
    }

    /** Real token size of the most recent request's context (input + output), or 0 if no response yet. */
    default long lastContextTokens() {
        return 0;
    }

    default long totalOutputTokens() {
        return 0;
    }

    /** Cumulative input tokens served from the provider cache this session (cache hits); 0 when not tracked. */
    default long totalCacheReadTokens() {
        return 0;
    }

    /** Cumulative input tokens written to the provider cache this session (cache misses that populate it). */
    default long totalCacheWriteTokens() {
        return 0;
    }

    /** Cumulative reasoning (thinking) tokens billed within the output count this session; 0 when not tracked. */
    default long totalReasoningTokens() {
        return 0;
    }

    /** Estimated cumulative spend this session, in USD. {@code 0} when the client does not track cost. */
    default double totalCost() {
        return 0;
    }

    /** The session cost budget in USD, or {@code null} when none is set. */
    default Double costBudget() {
        return null;
    }

    /** Whether some usage ran on a model with unknown pricing, so {@link #totalCost} understates the real spend. */
    default boolean hasUnpricedUsage() {
        return false;
    }

    /** Prompt-processing (prefill) and generation (decode) speed of the most recent request. */
    record RequestSpeed(double prefillTokensPerSec, double decodeTokensPerSec) {
    }

    /** Speed of the last completed request, or {@code null} if none has completed or it can't be measured. */
    default RequestSpeed lastRequestSpeed() {
        return null;
    }

    void abort();

    default ThinkingLevel thinkingLevel() {
        return ThinkingLevel.OFF;
    }

    default void setThinkingLevel(ThinkingLevel level) {}

    default void setProviderAndModel(String provider, String model, String apiKey) {
        throw new UnsupportedOperationException("LLM model selection is not supported by this client");
    }

    CompletableFuture<StreamResult> streamMessages(List<Message> messages, List<Tool> tools,
                                    BiConsumer<String, String> onPartial);

    /**
     * Summarizes messages for context compaction. The default has no summarizer and returns {@code null}, which the
     * compaction manager treats as "degraded" (a heuristic summary). Real providers override this.
     */
    default String summarize(List<Message> messages, String instructions, String previousSummary,
                                    BiConsumer<String, String> onPartial, String modelOverride, String systemPrompt) {
        return null;
    }

    @Override
    default void close() {}
}
