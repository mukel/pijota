package ai.pijota.extensions;

import ai.pijota.llm.LlmService;
import ai.pijota.messages.Message;

import java.util.List;

public interface AgentContext {
    LlmService llm();
    boolean isBusy();
    boolean isClosed();
    void abort();
    List<Message> messages();
    void compact(String instructions);
    void close();

    /** Send a user message to the agent. Triggers a turn when idle.
     *  When busy, uses steer delivery. */
    void sendUserMessage(String content);

    /** Send a user message with explicit delivery mode.
     *  deliverAs: "steer" (processed next turn start), "followUp" (processed after current turn) */
    void sendUserMessage(String content, String deliverAs);

    /** Add a custom extension message to the conversation (displayed, not sent to LLM). */
    void addCustomMessage(Message.Custom message);
}
