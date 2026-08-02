package org.pijota.extensions;

import org.pijota.llm.LlmClient;
import org.pijota.messages.Message;

import java.util.List;

/**
 * Bridge between {@link ExtensionRegistry} and the agent loop. Extension authors do <em>not</em> implement this; they
 * use {@link ExtensionRuntime} (reached via {@code api.runtime()}), which delegates here.
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

    /** Send a user message with an explicit {@link DeliverAs} mode (steer / follow-up / next-turn). */
    void sendUserMessage(String content, DeliverAs deliverAs);

    /** Add a custom extension message to the conversation (displayed, not sent to LLM). */
    void addCustomMessage(Message.Custom message);
}
