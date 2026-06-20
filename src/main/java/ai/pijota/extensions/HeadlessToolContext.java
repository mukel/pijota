package ai.pijota.extensions;

import ai.pijota.llm.LlmService;
import ai.pijota.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * Context passed to {@link Extension#contributeHeadlessTools} for each headless sub-agent invocation. Use
 * {@link #requested} to check whether a tool was explicitly requested before registering it.
 */
public record HeadlessToolContext(List<String> requestedTools, int depth, LlmService llm, ToolRegistry registry,
                                String systemPrompt, Path cwd) {
    public boolean requested(String name) {
        return requestedTools != null && requestedTools.contains(name);
    }
}
