package org.pijota.extensions;

import org.pijota.messages.Message;
import org.pijota.tools.Tool;

import java.nio.file.Path;
import java.util.List;

/**
 * All events extensions can subscribe to via {@link ExtensionAPI#on}. Covers the full lifecycle: session (start, ready,
 * shutdown, compaction), agent (start, end, turns), message streaming, tool calls and execution, provider requests,
 * model selection, user bash, and raw input.
 * <p>
 * Events are dispatched synchronously to each handler during the agent loop — handlers block the turn, so keep them
 * fast. See {@link ExtensionResult} for decision-hook return types.
 */
public sealed interface ExtensionEvent {
    default String name() {
        return switch (this) {
            case SessionStart ignored -> "session_start";
            case SessionBeforeCompact ignored -> "session_before_compact";
            case SessionCompact ignored -> "session_compact";
            case BeforeAgentStart ignored -> "before_agent_start";
            case AgentStart ignored -> "agent_start";
            case AgentEnd ignored -> "agent_end";
            case TurnStart ignored -> "turn_start";
            case TurnEnd ignored -> "turn_end";
            case MessageStart ignored -> "message_start";
            case MessageUpdate ignored -> "message_update";
            case MessageEnd ignored -> "message_end";
            case ToolCall ignored -> "tool_call";
            case ToolResult ignored -> "tool_result";
            case ToolExecutionStart ignored -> "tool_execution_start";
            case ToolExecutionEnd ignored -> "tool_execution_end";
            case BeforeProviderRequest ignored -> "before_provider_request";
            case ModelSelect ignored -> "model_select";
            case UserBash ignored -> "user_bash";
            case BashToolCall ignored -> "bash_tool_call";
            case Input e -> e.handled() ? "input_handled" : "input";
            case Error ignored -> "error";
        };
    }
    /** A new session file has been created or loaded — emitted once per session. */
    record SessionStart(Path sessionPath) implements ExtensionEvent {
    }
    /** Compaction is about to begin — {@code cancel} allows extensions to veto it. */
    record SessionBeforeCompact(boolean cancel, String instructions) implements ExtensionEvent {
    }
    /** Compaction has completed — the context window has been shrunk. */
    record SessionCompact() implements ExtensionEvent {
    }

    /**
     * Emitted just before the agent starts — mutate {@code systemPrompt} or {@code selectedTools} to influence the
     * turn.
     */
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

    /** Fired before and after execution — check {@code before}. */
    record ToolCall(String toolCallId, String toolName, String arguments, boolean before) implements ExtensionEvent {
    }

    record ToolResult(String toolCallId, String toolName, Tool.ToolResult result) implements ExtensionEvent {
    }

    /** A tool is about to begin executing (post-validation, pre-computation). */
    record ToolExecutionStart(String toolCallId, String toolName, String arguments) implements ExtensionEvent {
    }
    /** A tool has finished executing — {@code isError} indicates failure. */
    record ToolExecutionEnd(String toolCallId, String toolName, Tool.ToolResult result,
                                    boolean isError) implements ExtensionEvent {
    }

    /** Before the message list is sent to the provider (last chance to filter). */
    record BeforeProviderRequest(List<Message> messages) implements ExtensionEvent {
    }

    /** Model changed via /model, compaction auto-switch, or fallback. */
    record ModelSelect(String model, String previousModel, String source) implements ExtensionEvent {
    }

    /** User ran a command in bash mode (TUI), not a tool call. */
    record UserBash(String command, boolean excludeFromContext, Path cwd) implements ExtensionEvent {
    }

    /** The model invoked the bash tool — can be blocked or modified. */
    record BashToolCall(String toolCallId, String command, String workdir, long timeoutSec) implements ExtensionEvent {
    }

    /**
     * An agent turn failed (LLM/tool error after retries). Fired in addition to completing the turn exceptionally, so
     * extensions (e.g. an autonomous loop) can recover — {@code retryable} hints whether a backed-off retry is
     * sensible.
     */
    record Error(String message, boolean retryable) implements ExtensionEvent {
    }

    /** Raw user input before processing — {@code handled} short-circuits remaining handlers. */
    record Input(String text, boolean handled) implements ExtensionEvent {
        public Input(String text) {
            this(text, false);
        }

        public Input markHandled() {
            return new Input(text, true);
        }
    }
}
