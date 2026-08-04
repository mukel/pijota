package org.pijota.modes;

import org.pijota.agent.AgentEvent;
import org.pijota.messages.Message;
import org.pijota.messages.Usage;
import org.pijota.util.ErrorUtil;
import org.pijota.util.Util;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders {@link AgentEvent}s as newline-delimited JSON, one object per line, each with a {@code "type"}. This is what
 * makes {@code --mode json} usable for something other than waiting: a wrapper UI, a CI annotator or a progress bar can
 * follow a run as it happens instead of learning everything at once when the process exits.
 *
 * <p>
 * Lines are written and flushed as the events arrive, so a consumer reading line-by-line sees them live. Events reach
 * the listener on the agent thread while the final summary is written from the caller's, so a line must never
 * interleave with another; each is handed to {@link PrintStream#println(String)} whole, which is atomic, and the JSON
 * is built before that call rather than streamed into it.
 *
 * <p>
 * Unknown or purely internal events are skipped rather than rendered as a placeholder: a consumer should be able to
 * treat every line it receives as meaningful.
 */
public final class JsonEventStream {

    private final PrintStream out;

    public JsonEventStream(PrintStream out) {
        this.out = out;
    }

    /** Writes one line for {@code event}, or nothing if it carries no information for an external consumer. */
    public void emit(AgentEvent event) {
        Map<String, Object> line = describe(event);
        if (line != null)
            write(line);
    }

    /** Writes an arbitrary object as one line. Used for the terminal {@code result} summary. */
    public void write(Map<String, Object> line) {
        try {
            out.println(Util.MAPPER.writeValueAsString(line));
            out.flush();
        } catch (Exception ignored) {
            // A consumer that has gone away (closed pipe) must not take the run down with it.
        }
    }

    // package-private so the event shapes can be asserted without driving a whole run
    static Map<String, Object> describe(AgentEvent event) {
        return switch (event) {
            case AgentEvent.TurnStart(int turn) -> of("turn_start", "turn", turn);
            case AgentEvent.TurnEnd(int turn) -> of("turn_end", "turn", turn);
            case AgentEvent.MessageStreamStart(var type) -> of("stream_start", "messageType", type);
            case AgentEvent.MessageStreamDelta(var delta) -> of("delta", "text", delta);
            case AgentEvent.MessageStreamEnd(var type) -> of("stream_end", "messageType", type);
            // A machine-readable twin of the status line's progress: a wrapper can show a long tool call advancing
            // rather than a silent gap between stream_start and the tool result.
            case AgentEvent.ToolCallProgress(var toolName, int chars) ->
                of("tool_call_progress", "tool", toolName, "chars", chars);
            case AgentEvent.AssistantMessage(var msg, var usage) -> assistant(msg, usage);
            // The supplier defers serializing arguments until something asks; asking is the point here.
            case AgentEvent.ToolStart(var name, var id, var arguments) -> of("tool_start", "tool", name, "toolCallId",
                                            id, "arguments", arguments == null ? null : arguments.get());
            case AgentEvent.BashExecutionUpdate(var id, var delta) ->
                of("tool_output", "toolCallId", id, "delta", delta);
            case AgentEvent.ToolResult(var msg) -> of("tool_result", "tool", msg.toolName(), "toolCallId",
                                            msg.toolCallId(), "isError", msg.isError(), "content", msg.content());
            case AgentEvent.Compaction(var ignored, int oldCount, int chars, int tokens, var summary, var reason) ->
                of("compaction", "reason", reason, "oldMessageCount", oldCount, "estimatedChars", chars,
                                                "estimatedTokens", tokens, "summary", summary);
            case AgentEvent.HistoryChanged(var reason) -> of("history_changed", "reason", reason);
            case AgentEvent.CustomMessage(var msg) ->
                of("custom", "customType", msg.customType(), "content", msg.content());
            case AgentEvent.Error(var t) -> of("error", "message", ErrorUtil.message(t));
            // Compaction streaming duplicates what `compaction` reports once it lands, and is noisy per token.
            case AgentEvent.CompactionStream ignored -> null;
            case AgentEvent.CompactionStreamEnd ignored -> null;
        };
    }

    /**
     * Assistant messages arrive repeatedly with cumulative content while streaming; the deltas already carry that. Only
     * the settled message (the one that comes with usage) is worth a line.
     */
    private static Map<String, Object> assistant(Message.Assistant msg, Usage usage) {
        if (usage == null)
            return null;
        var m = of("assistant", "content", msg.content());
        if (msg.thinking() != null && !msg.thinking().isBlank())
            m.put("thinking", msg.thinking());
        m.put("usage", usage); // the record serializes to its five components; no hand-built key list to drift
        return m;
    }

    /**
     * A line: {@code type} first, then key/value pairs. A LinkedHashMap, not Map.of: order matters and values may be
     * null.
     */
    private static Map<String, Object> of(String type, Object... keyValues) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        for (int i = 0; i + 1 < keyValues.length; i += 2)
            m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }
}
