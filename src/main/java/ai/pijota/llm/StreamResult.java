package ai.pijota.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

public final class StreamResult {
    private final StringBuilder contentBuilder = new StringBuilder();
    private final StringBuilder thinkingBuilder = new StringBuilder();
    private List<ToolExecutionRequest> toolRequests;
    private TokenUsage usage;

    public void appendContent(String text) {
        if (text != null) {
            contentBuilder.append(text);
        }
    }

    public void setContent(String text) {
        contentBuilder.setLength(0);
        if (text != null) {
            contentBuilder.append(text);
        }
    }

    public void setThinking(String text) {
        thinkingBuilder.setLength(0);
        if (text != null) {
            thinkingBuilder.append(text);
        }
    }

    public void setToolRequests(List<ToolExecutionRequest> requests) {
        this.toolRequests = requests;
    }

    public void setUsage(TokenUsage usage) {
        this.usage = usage;
    }

    public String content() {
        return contentBuilder.toString();
    }

    public String thinking() {
        return thinkingBuilder.toString();
    }

    public List<ToolExecutionRequest> toolRequests() {
        return toolRequests != null ? toolRequests : List.of();
    }

    public boolean hasToolCalls() {
        return toolRequests != null && !toolRequests.isEmpty();
    }

    public TokenUsage usage() {
        return usage;
    }
}
