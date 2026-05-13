package ai.pijota.extensions;

import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import java.nio.file.Path;
import java.util.List;

public sealed interface ExtensionEvent {
    record SessionStart(Path sessionPath) implements ExtensionEvent {}
    record SessionReady() implements ExtensionEvent {}
    record SessionShutdown() implements ExtensionEvent {}
    record SessionBeforeCompact(boolean cancel, String instructions) implements ExtensionEvent {}
    record SessionCompact() implements ExtensionEvent {}

    record BeforeAgentStart(List<Message> messages, StringBuilder systemPrompt, List<Tool.ToolInfo> selectedTools) implements ExtensionEvent {}
    record AgentStart() implements ExtensionEvent {}
    record AgentEnd() implements ExtensionEvent {}

    record TurnStart(int turnNumber) implements ExtensionEvent {}
    record TurnEnd(int turnNumber) implements ExtensionEvent {}

    record MessageStart() implements ExtensionEvent {}
    record MessageUpdate(String delta) implements ExtensionEvent {}
    record MessageEnd() implements ExtensionEvent {}

    record ToolCall(String toolCallId, String toolName, String arguments, boolean before) implements ExtensionEvent {}

    record ToolResult(String toolCallId, String toolName, Tool.ToolResult result) implements ExtensionEvent {}

    record ToolExecutionStart(String toolCallId, String toolName, String arguments) implements ExtensionEvent {}
    record ToolExecutionEnd(String toolCallId, String toolName, Tool.ToolResult result, boolean isError) implements ExtensionEvent {}

    record BeforeProviderRequest(List<Message> messages) implements ExtensionEvent {}

    record ModelSelect(String model, String previousModel, String source) implements ExtensionEvent {}

    record UserBash(String command, boolean excludeFromContext, Path cwd) implements ExtensionEvent {}

    record Input(String text, boolean handled) implements ExtensionEvent {
        public Input(String text) { this(text, false); }
        public Input markHandled() { return new Input(text, true); }
    }
}
