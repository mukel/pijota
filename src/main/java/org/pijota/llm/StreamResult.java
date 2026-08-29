package org.pijota.llm;

import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;

import java.util.List;

/**
 * Accumulates one streamed response: content, thinking, tool calls, usage and the finish reason arrive in pieces and
 * are read as a whole once the stream ends.
 */
public final class StreamResult {
    private final StringBuilder contentBuilder = new StringBuilder();
    private final StringBuilder thinkingBuilder = new StringBuilder();
    private List<ToolCall> toolCalls;
    private Usage usage;
    // Provider stop reason ("STOP", "LENGTH", …); null when the provider did not report one.
    private String finishReason;

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

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public String content() {
        return contentBuilder.toString();
    }

    public String thinking() {
        return thinkingBuilder.toString();
    }

    public List<ToolCall> toolCalls() {
        return toolCalls != null ? toolCalls : List.of();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public Usage usage() {
        return usage;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    /** True when the model stopped because it hit the output cap: whatever content arrived is partial. */
    public boolean lengthLimited() {
        return "LENGTH".equals(finishReason);
    }
}
