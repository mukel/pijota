package ai.pijota.agent;

import ai.pijota.messages.Message;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.function.Supplier;

public sealed interface AgentEvent {

    record TurnStart(int turn) implements AgentEvent {}
    record TurnEnd(int turn) implements AgentEvent {}

    record AssistantMessage(Message.Assistant message, TokenUsage usage) implements AgentEvent {}

    record ToolStart(String toolName, String toolCallId, Supplier<String> argumentsSupplier) implements AgentEvent {}
    record ToolResult(Message.ToolResult message) implements AgentEvent {}

    record MessageStreamStart(String messageType) implements AgentEvent {}
    record MessageStreamDelta(String delta) implements AgentEvent {}
    record MessageStreamEnd(String messageType) implements AgentEvent {}
    record CompactionStream(String content, String thinking) implements AgentEvent {}
    record CompactionStreamEnd() implements AgentEvent {}

    record Compaction(List<Message> compactedMessages, int oldCount, int estimatedChars, int estimatedTokens, String summary, String reason) implements AgentEvent {}
    record Error(Throwable throwable) implements AgentEvent {}

    static AgentEvent turnStart(int turn) { return new TurnStart(turn); }
    static AgentEvent turnEnd(int turn) { return new TurnEnd(turn); }
    static AgentEvent assistantMessage(Message.Assistant msg, TokenUsage usage) {
        return new AssistantMessage(msg, usage);
    }
    static AgentEvent toolStart(String name, String toolCallId, Supplier<String> args) {
        return new ToolStart(name, toolCallId, args);
    }
    static AgentEvent toolResult(Message.ToolResult msg) { return new ToolResult(msg); }
    static AgentEvent messageStreamStart(String type) { return new MessageStreamStart(type); }
    static AgentEvent messageStreamDelta(String delta) { return new MessageStreamDelta(delta); }
    static AgentEvent messageStreamEnd(String type) { return new MessageStreamEnd(type); }
    static AgentEvent compactionStream(String content, String thinking) { return new CompactionStream(content, thinking); }
    static AgentEvent compactionStreamEnd() { return new CompactionStreamEnd(); }
    static AgentEvent compaction(List<Message> compacted, int oldCount, int estimatedChars, int estimatedTokens, String summary, String reason) {
        return new Compaction(compacted, oldCount, estimatedChars, estimatedTokens, summary, reason);
    }
    static AgentEvent error(Throwable t) { return new Error(t); }
}
