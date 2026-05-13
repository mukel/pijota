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
}
