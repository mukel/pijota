package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;
import org.pijota.util.ErrorUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The session file's format: one JSONL entry in, one {@link Message} out, and back again.
 *
 * <p>
 * Split out of {@link SessionManager}, which manages files, write locks and the current branch position -- none of
 * which any of this needs. These are pure functions over a parsed entry, and keeping them beside the locking made a
 * class where "what a session file contains" and "how a session file is written" had to be read together to change
 * either. The seam is the one the code already had: not one of these ever touched the manager's state.
 */
public final class SessionCodec {
    private static final Logger log = LoggerFactory.getLogger(SessionCodec.class);

    private SessionCodec() {}

    static List<Message.Attachment> deserializeAttachments(Object raw) {
        if (!(raw instanceof List<?> list))
            return List.of();
        var out = new ArrayList<Message.Attachment>();
        for (Object o : list)
            if (o instanceof Map<?, ?> m)
                out.add(new Message.Attachment((String) m.get("mime"), (String) m.get("base64"),
                                                (String) m.get("filename")));
        return out;
    }

    static void serializeMessage(Map<String, Object> entry, Message msg) {
        switch (msg) {
            case Message.User u -> {
                entry.put("type", "message");
                entry.put("role", "user");
                entry.put("content", u.content());
                if (!u.attachments().isEmpty()) {
                    var atts = new ArrayList<Map<String, Object>>();
                    for (var a : u.attachments()) {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("mime", a.mime());
                        m.put("base64", a.base64());
                        if (a.filename() != null)
                            m.put("filename", a.filename());
                        atts.add(m);
                    }
                    entry.put("attachments", atts);
                }
            }
            case Message.Assistant a -> {
                entry.put("type", "message");
                entry.put("role", "assistant");
                entry.put("content", a.content());
                if (a.toolCalls() != null && !a.toolCalls().isEmpty()) {
                    entry.put("tool_calls", a.toolCalls().stream().map(tc -> Map.of("id", tc.id(), "name", tc.name(),
                                                    "arguments", tc.arguments())).toList());
                }
                if (a.thinking() != null)
                    entry.put("thinking", a.thinking());
                if (a.sourceProvider() != null && !a.sourceProvider().isBlank())
                    entry.put("source_provider", a.sourceProvider());
                if (a.sourceModel() != null && !a.sourceModel().isBlank())
                    entry.put("source_model", a.sourceModel());
                if (a.usage() != null)
                    entry.put("usage", serializeUsage(a.usage()));
            }
            case Message.ToolResult tr -> {
                entry.put("type", "message");
                entry.put("role", "tool_result");
                entry.put("tool_call_id", tr.toolCallId());
                entry.put("tool_name", tr.toolName());
                entry.put("content", tr.content());
                entry.put("is_error", tr.isError());
                if (tr.details() != null && !tr.details().isEmpty())
                    entry.put("details", tr.details());
            }
            case Message.System s -> {
                entry.put("type", "message");
                entry.put("role", "system");
                entry.put("content", s.content());
            }
            case Message.BranchSummary bs -> {
                entry.put("type", "branch_summary");
                entry.put("role", "user");
                entry.put("summary", bs.summary());
                entry.put("content", "[Branch context]\n" + bs.summary());
            }
            case Message.CompactionSummary cs -> {
                entry.put("type", "compaction");
                entry.put("role", "user");
                entry.put("summary", cs.summary());
                entry.put("content", "[Previous conversation summary]\n" + cs.summary());
            }
            case Message.Custom c -> {
                entry.put("type", "custom_message");
                entry.put("role", "custom");
                entry.put("custom_type", c.customType());
                entry.put("content", c.content());
                if (c.details() != null && !c.details().isEmpty())
                    entry.put("details", c.details());
            }
            default -> throw new IllegalArgumentException("Unknown message type: " + msg.getClass().getSimpleName());
        }
    }

    static Map<String, Object> serializeUsage(Usage usage) {
        var out = new LinkedHashMap<String, Object>();
        out.put("inputTokens", usage.inputTokens());
        out.put("outputTokens", usage.outputTokens());
        out.put("cacheReadTokens", usage.cacheReadTokens());
        out.put("cacheWriteTokens", usage.cacheWriteTokens());
        out.put("reasoningTokens", usage.reasoningTokens());
        return out;
    }

    /**
     * Session-file entry types that carry no conversation content: bookkeeping written between messages.
     *
     * <p>
     * They still hold ids and are still linked into the parent chain, so the tree walk needs them and only the counting
     * does not. One list with two readers -- the loader, which turns entries into messages, and {@code /tree}, whose
     * header used to size itself with {@code entries.size()} and so counted every model switch and cache note as a
     * piece of the conversation.
     */
    private static final Set<String> METADATA_TYPES = Set.of("model_change", "thinking_level_change", "cache_metadata",
                                    "session_info", "compaction", "session", "branch_summary");

    /** True when this entry is bookkeeping rather than something said. See {@link #METADATA_TYPES}. */
    public static boolean isMetadataEntry(Map<String, Object> entry) {
        return METADATA_TYPES.contains((String) entry.getOrDefault("type", "message"));
    }

    @SuppressWarnings("unchecked")
    static Message deserializeEntry(Map<String, Object> entry) {
        if (isMetadataEntry(entry))
            return null;

        String role = (String) entry.getOrDefault("role", "user");
        UUID id;
        Instant ts;
        try {
            id = UUID.fromString((String) entry.getOrDefault("id", UUID.randomUUID().toString()));
            ts = Instant.parse((String) entry.getOrDefault("timestamp", Instant.now().toString()));
        } catch (RuntimeException e) {
            // Any field we cannot make sense of means we skip this entry, which is what the narrower catch here meant
            // to say: it named IllegalArgumentException for a bad id and ClassCastException for a wrong type, and
            // missed DateTimeParseException, which is neither. A corrupted timestamp then threw out of the whole load
            // and the session would not open at all -- one bad byte costing a conversation.
            log.warn("Skipping a session entry with an unreadable id or timestamp: {}", ErrorUtil.message(e));
            return null;
        }

        return switch (role) {
            case "user" -> {
                String content = (String) entry.getOrDefault("content", "");
                yield new Message.User(id, ts, content, deserializeAttachments(entry.get("attachments")));
            }
            case "assistant" -> {
                String content = (String) entry.get("content");
                String thinking = (String) entry.get("thinking");
                String sourceProvider = (String) entry.get("source_provider");
                String sourceModel = (String) entry.get("source_model");
                List<ToolCall> tcs = List.of();
                Object tcObj = entry.get("tool_calls");
                if (tcObj instanceof List<?> list) {
                    tcs = list.stream().filter(item -> item instanceof Map).map(item -> {
                        Map<String, Object> m = (Map<String, Object>) item;
                        return new ToolCall((String) m.get("id"), (String) m.get("name"),
                                                        m.get("arguments") instanceof String s
                                                                                        ? s
                                                                                        : m.get("arguments") != null
                                                                                                                        ? m.get("arguments").toString()
                                                                                                                        : "{}");
                    }).toList();
                }
                yield new Message.Assistant(id, ts, content, tcs, thinking, sourceProvider, sourceModel,
                                                SessionUsage.usageFrom(entry.get("usage")));
            }
            case "tool", "tool_result" -> {
                String tcId = (String) entry.get("tool_call_id");
                String tcName = (String) entry.get("tool_name");
                String content = (String) entry.get("content");
                boolean isError = Boolean.TRUE.equals(entry.get("is_error"));
                Map<String, Object> details = entry.get("details") instanceof Map<?, ?> m
                                                ? (Map<String, Object>) m
                                                : Map.of();
                yield new Message.ToolResult(id, ts, tcId, tcName, content, isError, details);
            }
            case "system" -> {
                String content = (String) entry.getOrDefault("content", "");
                yield new Message.System(id, ts, content);
            }
            default -> {
                String content = (String) entry.getOrDefault("content", "");
                yield new Message.User(id, ts, content, deserializeAttachments(entry.get("attachments")));
            }
        };
    }
}
