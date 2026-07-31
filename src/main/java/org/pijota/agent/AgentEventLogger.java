package org.pijota.agent;

import org.pijota.util.ErrorUtil;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single, non-invasive trace of the agent's behaviour: registered as an {@link AgentLoop} listener, it logs the whole
 * {@link AgentEvent} stream to the {@code org.pijota.agent.events} logger so a turn can be reconstructed from the log
 * file. Levels are chosen so the default (INFO) gives a readable turn-by-turn narrative, while {@code DEBUG}/{@code
 * TRACE} add stream boundaries, tool arguments, and the per-delta firehose for deep diagnosis:
 *
 * <ul>
 * <li>INFO: turn start/end, each tool call + result, assistant message + token usage, compaction</li>
 * <li>DEBUG: message-stream boundaries, tool arguments (secret-scrubbed), custom/history events</li>
 * <li>TRACE: per-token stream deltas</li>
 * <li>ERROR: a turn failure</li>
 * </ul>
 *
 * Arguments and error text are routed through {@link ErrorUtil} so API keys / bearer tokens never reach the log.
 */
final class AgentEventLogger {
    private static final Logger log = LoggerFactory.getLogger("org.pijota.agent.events");

    void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.TurnStart e -> log.info("turn {} ▶ start", e.turn());
            case AgentEvent.TurnEnd e -> log.info("turn {} ■ end", e.turn());
            case AgentEvent.AssistantMessage e -> {
                // Only the final completion carries token usage; the pre-stream placeholder and the per-chunk streaming
                // updates have null usage and would flood INFO, so they drop to TRACE; the MessageStreamDelta firehose
                // already covers the streaming detail. (A provider that omits token counts logs its completion at TRACE
                // too, but the surrounding turn/tool events still trace the turn at INFO.)
                if (e.usage() != null) {
                    var m = e.message();
                    int calls = m.toolCalls() == null ? 0 : m.toolCalls().size();
                    log.info("assistant message · {} tool call(s) · tokens in={} out={}", calls,
                                                    e.usage().inputTokens(), e.usage().outputTokens());
                } else {
                    log.trace("assistant message (streaming/placeholder)");
                }
            }
            case AgentEvent.ToolStart e -> {
                log.info("tool ▶ {} [{}]", e.toolName(), e.toolCallId());
                if (log.isDebugEnabled())
                    log.debug("  args: {}", ErrorUtil.sanitize(safe(e.argumentsSupplier())));
            }
            case AgentEvent.BashExecutionUpdate e ->
                log.trace("bash delta [{}] · {} chars", e.toolCallId(), e.delta() == null ? 0 : e.delta().length());
            case AgentEvent.ToolResult e -> {
                var m = e.message();
                log.info("tool ■ {} [{}] · {} · {} chars", m.toolName(), m.toolCallId(), m.isError() ? "ERROR" : "ok",
                                                m.content() == null ? 0 : m.content().length());
            }
            case AgentEvent.Compaction e -> log.info("compaction · {} → {} messages · ~{} tokens", e.oldCount(),
                                            e.compactedMessages().size(), e.estimatedTokens());
            case AgentEvent.Error e -> log.error("turn error: {}", ErrorUtil.message(e.throwable()));
            case AgentEvent.MessageStreamStart e -> log.debug("stream ▶ {}", e.messageType());
            case AgentEvent.MessageStreamEnd e -> log.debug("stream ■ {}", e.messageType());
            case AgentEvent.HistoryChanged e -> log.debug("history changed · {}", e.reason());
            case AgentEvent.CustomMessage e -> log.debug("custom message · {}", e.message().customType());
            case AgentEvent.MessageStreamDelta e ->
                log.trace("delta · {} chars", e.delta() == null ? 0 : e.delta().length());
            case AgentEvent.CompactionStream e -> log.trace("compaction stream delta");
            case AgentEvent.CompactionStreamEnd e -> log.trace("compaction stream end");
        }
    }

    private static String safe(Supplier<String> args) {
        try {
            return args == null ? "" : args.get();
        } catch (Exception e) {
            return "<args unavailable>";
        }
    }
}
