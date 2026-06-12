package ai.pijota.extensions;

import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import java.nio.file.Path;
import java.util.List;

public sealed interface ExtensionEvent {
    default String name() {
        return switch (this) {
            case SessionStart _ -> "session_start";
            case SessionReady _ -> "session_ready";
            case SessionShutdown _ -> "session_shutdown";
            case SessionBeforeCompact _ -> "session_before_compact";
            case SessionCompact _ -> "session_compact";
            case BeforeAgentStart _ -> "before_agent_start";
            case AgentStart _ -> "agent_start";
            case AgentEnd _ -> "agent_end";
            case TurnStart _ -> "turn_start";
            case TurnEnd _ -> "turn_end";
            case MessageStart _ -> "message_start";
            case MessageUpdate _ -> "message_update";
            case MessageEnd _ -> "message_end";
            case ToolCall _ -> "tool_call";
            case ToolResult _ -> "tool_result";
            case ToolExecutionStart _ -> "tool_execution_start";
            case ToolExecutionEnd _ -> "tool_execution_end";
            case BeforeProviderRequest _ -> "before_provider_request";
            case ModelSelect _ -> "model_select";
            case UserBash _ -> "user_bash";
            case BashToolCall _ -> "bash_tool_call";
            case Input e -> e.handled() ? "input_handled" : "input";
        };
    }
    record SessionStart(Path sessionPath) implements ExtensionEvent {
    }
    record SessionReady() implements ExtensionEvent {
    }
    record SessionShutdown() implements ExtensionEvent {
    }
    record SessionBeforeCompact(boolean cancel, String instructions) implements ExtensionEvent {
    }
    record SessionCompact() implements ExtensionEvent {
    }

    record BeforeAgentStart(List<Message> messages, StringBuilder systemPrompt,
                                    List<Tool.ToolInfo> selectedTools) implements ExtensionEvent {
    }
    record AgentStart() implements ExtensionEvent {
    }
    record AgentEnd() implements ExtensionEvent {
    }

    record TurnStart(int turnNumber) implements ExtensionEvent {
    }
    record TurnEnd(int turnNumber) implements ExtensionEvent {
    }

    record MessageStart() implements ExtensionEvent {
    }
    record MessageUpdate(String delta) implements ExtensionEvent {
    }
    record MessageEnd() implements ExtensionEvent {
    }

    record ToolCall(String toolCallId, String toolName, String arguments, boolean before) implements ExtensionEvent {
    }

    record ToolResult(String toolCallId, String toolName, Tool.ToolResult result) implements ExtensionEvent {
    }

    record ToolExecutionStart(String toolCallId, String toolName, String arguments) implements ExtensionEvent {
    }
    record ToolExecutionEnd(String toolCallId, String toolName, Tool.ToolResult result,
                                    boolean isError) implements ExtensionEvent {
    }

    record BeforeProviderRequest(List<Message> messages) implements ExtensionEvent {
    }

    record ModelSelect(String model, String previousModel, String source) implements ExtensionEvent {
    }

    record UserBash(String command, boolean excludeFromContext, Path cwd) implements ExtensionEvent {
    }

    record BashToolCall(String toolCallId, String command, String workdir, long timeoutSec) implements ExtensionEvent {
    }

    record Input(String text, boolean handled) implements ExtensionEvent {
        public Input(String text) {
            this(text, false);
        }

        public Input markHandled() {
            return new Input(text, true);
        }
    }
}
