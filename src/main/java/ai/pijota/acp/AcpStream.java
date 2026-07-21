package ai.pijota.acp;

import ai.pijota.agent.AgentEvent;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.messages.Message;
import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Translates one session's {@link AgentEvent} stream into ordered ACP {@code session/update} payloads. This is the
 * streaming heart of the frontend, kept separate from the connection/session so it can be unit-tested: give it a token
 * source and feed it events, assert the payloads. One instance per loaded session (it holds the running thinking
 * prefix). The frontend wraps each returned payload with the live {@code sessionId} and sends it.
 */
final class AcpStream {

    /** Assistant text arrives as deltas; reasoning has no delta event, so we diff it against this running prefix. */
    private String lastThinking = "";

    private final LongSupplier contextTokens;

    AcpStream(LongSupplier contextTokens) {
        this.contextTokens = contextTokens;
    }

    /** The {@code session/update} payloads to emit for one event, in order (possibly none). */
    List<ObjectNode> translate(AgentEvent event) {
        return switch (event) {
            case AgentEvent.TurnStart ignored -> {
                lastThinking = "";
                yield List.of();
            }
            case AgentEvent.MessageStreamDelta d ->
                d.delta().isEmpty() ? List.of() : List.of(AcpMessages.textChunk("agent_message_chunk", d.delta()));
            case AgentEvent.AssistantMessage am -> assistant(am);
            case AgentEvent.ToolStart ts ->
                List.of(AcpMessages.toolCall(ts.toolName(), ts.toolCallId(), ts.argumentsSupplier().get()));
            case AgentEvent.ToolResult tr -> {
                Message.ToolResult m = tr.message();
                yield List.of(AcpMessages.toolResult(m.toolCallId(), m.content(), m.isError()));
            }
            default -> List.of(); // compaction/history/custom/error not surfaced yet
        };
    }

    private List<ObjectNode> assistant(AgentEvent.AssistantMessage am) {
        var out = new ArrayList<ObjectNode>();
        String thinking = am.message().thinking();
        if (thinking != null && !thinking.isEmpty()) {
            String delta = Util.deltaPrefix(thinking, lastThinking);
            lastThinking = thinking;
            if (!delta.isEmpty())
                out.add(AcpMessages.textChunk("agent_thought_chunk", delta));
        }
        if (am.usage() != null) { // non-null only on the final frame of a model response
            long size = ModelRegistry.resolveContextWindow(am.message().sourceProvider(), am.message().sourceModel());
            out.add(AcpMessages.usageUpdate(contextTokens.getAsLong(), size));
        }
        return out;
    }
}
