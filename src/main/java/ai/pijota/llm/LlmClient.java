package ai.pijota.llm;

import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public interface LlmClient extends AutoCloseable {
    String provider();
    String model();
    default String apiKey() { return ""; }
    default long totalInputTokens() { return 0; }
    default long totalOutputTokens() { return 0; }
    boolean streamsWithTools();
    void abort();

    default ThinkingLevel thinkingLevel() { return ThinkingLevel.OFF; }
    default void setThinkingLevel(ThinkingLevel level) {}
    default void setProviderAndModel(String provider, String model, String apiKey) {
        throw new UnsupportedOperationException("LLM model selection is not supported by this client");
    }

    CompletableFuture<StreamResult> streamMessages(
        List<Message> messages,
        List<Tool> tools,
        BiConsumer<String, String> onPartial
    );

    @Override default void close() {}
}
