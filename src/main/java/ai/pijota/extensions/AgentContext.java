package ai.pijota.extensions;

import ai.pijota.messages.Message;
import ai.pijota.llm.LlmClient;

import java.util.List;

/**
 * Bridge between {@link ExtensionRegistry} and the agent loop. Extension authors do <em>not</em> implement this — they
 * use {@link ExtensionRuntime} and {@link ExtensionContext} which delegate here.
 */
public interface AgentContext {
    LlmClient llm();

    boolean isBusy();

    boolean isClosed();

    void abort();

    List<Message> messages();

    void compact(String instructions);

    void close();

    /**
     * Send a user message to the agent. Triggers a turn when idle. When busy, uses steer delivery.
     */
    void sendUserMessage(String content);

    /**
     * Send a user message with explicit delivery mode. deliverAs: "steer" (processed at the next turn start),
     * "followUp" (processed after the current turn), or "nextTurn" (delivered when the agent is next idle — the
     * default).
     */
    void sendUserMessage(String content, String deliverAs);

    /** Add a custom extension message to the conversation (displayed, not sent to LLM). */
    void addCustomMessage(Message.Custom message);
}
