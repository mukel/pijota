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

    private static Map<String, Object> describe(AgentEvent event) {
        return switch (event) {
            case AgentEvent.TurnStart(int turn) -> of("turn_start", "turn", turn);
            case AgentEvent.TurnEnd(int turn) -> of("turn_end", "turn", turn);
            case AgentEvent.MessageStreamStart(var type) -> of("stream_start", "messageType", type);
            case AgentEvent.MessageStreamDelta(var delta) -> of("delta", "text", delta);
            case AgentEvent.MessageStreamEnd(var type) -> of("stream_end", "messageType", type);
            case AgentEvent.AssistantMessage(var msg, var usage) -> assistant(msg, usage);
            case AgentEvent.ToolStart(var name, var id, var arguments) -> {
                var m = of("tool_start", "tool", name);
                m.put("toolCallId", id);
                // The supplier defers serializing arguments until something asks; asking is the point here.
                m.put("arguments", arguments == null ? null : arguments.get());
                yield m;
            }
            case AgentEvent.BashExecutionUpdate(var id, var delta) -> {
                var m = of("tool_output", "toolCallId", id);
                m.put("delta", delta);
                yield m;
            }
            case AgentEvent.ToolResult(var msg) -> {
                var m = of("tool_result", "tool", msg.toolName());
                m.put("toolCallId", msg.toolCallId());
                m.put("isError", msg.isError());
                m.put("content", msg.content());
                yield m;
            }
            case AgentEvent.Compaction(var ignored, int oldCount, int chars, int tokens, var summary, var reason) -> {
                var m = of("compaction", "reason", reason);
                m.put("oldMessageCount", oldCount);
                m.put("estimatedChars", chars);
                m.put("estimatedTokens", tokens);
                m.put("summary", summary);
                yield m;
            }
            case AgentEvent.HistoryChanged(var reason) -> of("history_changed", "reason", reason);
            case AgentEvent.CustomMessage(var msg) -> {
                var m = of("custom", "customType", msg.customType());
                m.put("content", msg.content());
                yield m;
            }
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
        m.put("usage", usage(usage));
        return m;
    }

    /** The usage record as a plain map, in the same shape the final {@code result} line uses. */
    public static Map<String, Object> usage(Usage u) {
        return Map.of("inputTokens", u.inputTokens(), "outputTokens", u.outputTokens(), "cacheReadTokens",
                                        u.cacheReadTokens(), "cacheWriteTokens", u.cacheWriteTokens(),
                                        "reasoningTokens", u.reasoningTokens());
    }

    private static Map<String, Object> of(String type, String key, Object value) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put(key, value);
        return m;
    }
}
