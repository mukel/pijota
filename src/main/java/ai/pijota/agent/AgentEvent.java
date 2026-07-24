package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.messages.Usage;

import java.util.List;
import java.util.function.Supplier;

public sealed interface AgentEvent {

    record TurnStart(int turn) implements AgentEvent {
    }
    record TurnEnd(int turn) implements AgentEvent {
    }

    record AssistantMessage(Message.Assistant message, Usage usage) implements AgentEvent {
    }

    record ToolStart(String toolName, String toolCallId, Supplier<String> argumentsSupplier) implements AgentEvent {
    }
    record BashExecutionUpdate(String toolCallId, String delta) implements AgentEvent {
    }
    record ToolResult(Message.ToolResult message) implements AgentEvent {
    }

    record MessageStreamStart(String messageType) implements AgentEvent {
    }
    record MessageStreamDelta(String delta) implements AgentEvent {
    }
    record MessageStreamEnd(String messageType) implements AgentEvent {
    }
    record CompactionStream(String content, String thinking) implements AgentEvent {
    }
    record CompactionStreamEnd() implements AgentEvent {
    }

    record Compaction(List<Message> compactedMessages, int oldCount, int estimatedChars, int estimatedTokens,
                                    String summary, String reason) implements AgentEvent {
    }

    /**
     * The conversation history was replaced wholesale (new session, load/resume, undo, fork) — not an incremental
     * append. Frontends that mirror the transcript should re-render it from {@code session.messages()}. {@code reason}
     * is a short tag (e.g. {@code "new"}, {@code "load"}, {@code "undo"}, {@code "fork"}).
     */
    record HistoryChanged(String reason) implements AgentEvent {
    }
    record CustomMessage(Message.Custom message) implements AgentEvent {
    }
    record Error(Throwable throwable) implements AgentEvent {
    }
}
