package ai.pijota.extensions;

import ai.pijota.llm.LlmService;
import ai.pijota.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

public record HeadlessToolContext(
    List<String> requestedTools,
    int depth,
    LlmService llm,
    ToolRegistry registry,
    String systemPrompt,
    Path cwd
) {
    public boolean requested(String name) {
        return requestedTools != null && requestedTools.contains(name);
    }
}
