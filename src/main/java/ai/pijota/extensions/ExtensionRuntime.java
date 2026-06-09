package ai.pijota.extensions;

import ai.pijota.llm.LlmClient;
import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ExtensionRuntime {
    LlmClient llm();
    Path cwd();
    String systemPrompt();
    List<Message> messages();
    ToolRegistry tools();
    List<Tool.ToolInfo> activeTools();
    void setActiveTools(List<String> toolNames);
    List<Tool.ToolInfo> allTools();
    String sessionName();
    void setSessionName(String name);
    boolean isIdle();
    boolean isClosed();
    void abort();
    void compact(String instructions);
    void setStatus(String message);
    void appendStatus(String message);
    void setStatus(String key, String message);
    void clearStatus(String key);
    boolean getFlag(String name, boolean defaultValue);
    Optional<String> getFlag(String name);
    ExecResult exec(List<String> command);

    record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean success() { return exitCode == 0; }
    }
}
