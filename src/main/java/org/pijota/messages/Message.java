package org.pijota.messages;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sealed hierarchy of all conversation message types: user messages, assistant responses (with tool calls and optional
 * thinking), tool results, system messages, compaction/branch summaries, and custom extension messages. Every message
 * has a unique ID and a creation timestamp.
 */
public sealed interface Message {
    UUID id();

    Instant timestamp();

    /** An image (or other media) attached to a user message: {@code base64} payload + its {@code mime} type. */
    record Attachment(String mime, String base64, String filename) {
        /** Provider hard limit (~Anthropic 5MB); larger media is dropped before the request to avoid an API error. */
        public static final long MAX_BYTES = 5L * 1024 * 1024;

        /** Approximate decoded size from the base64 length (avoids decoding just to measure). */
        public long approxBytes() {
            return base64 == null ? 0 : (long) base64.length() * 3 / 4;
        }
    }

    record User(UUID id, Instant timestamp, String content, List<Attachment> attachments) implements Message {
        public User {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public User(String content) {
            this(UUID.randomUUID(), Instant.now(), content, List.of());
        }

        public User(String content, List<Attachment> attachments) {
            this(UUID.randomUUID(), Instant.now(), content, attachments);
        }
    }

    record Assistant(UUID id, Instant timestamp, String content, List<ToolCall> toolCalls, String thinking,
                                    String sourceProvider, String sourceModel, Usage usage) implements Message {
        public Assistant {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public Assistant(String content, List<ToolCall> toolCalls, String thinking) {
            this(UUID.randomUUID(), Instant.now(), content, toolCalls, thinking, null, null, null);
        }

        public Assistant(UUID id, Instant timestamp, String content, List<ToolCall> toolCalls, String thinking) {
            this(id, timestamp, content, toolCalls, thinking, null, null, null);
        }

        public Assistant(UUID id, Instant timestamp, String content, List<ToolCall> toolCalls, String thinking,
                                        String sourceProvider, String sourceModel) {
            this(id, timestamp, content, toolCalls, thinking, sourceProvider, sourceModel, null);
        }

        public Assistant withSource(String provider, String model) {
            return new Assistant(id, timestamp, content, toolCalls, thinking, provider, model, usage);
        }

        public Assistant withThinking(String newThinking) {
            return new Assistant(id, timestamp, content, toolCalls, newThinking, sourceProvider, sourceModel, usage);
        }

        public Assistant withUsage(Usage usage) {
            return new Assistant(id, timestamp, content, toolCalls, thinking, sourceProvider, sourceModel, usage);
        }
    }

    record ToolResult(UUID id, Instant timestamp, String toolCallId, String toolName, String content, boolean isError,
                                    Map<String, Object> details) implements Message {
        public ToolResult(String toolCallId, String toolName, String content, boolean isError) {
            this(UUID.randomUUID(), Instant.now(), toolCallId, toolName, content, isError, Map.of());
        }

        public ToolResult(String toolCallId, String toolName, String content, boolean isError,
                                        Map<String, Object> details) {
            this(UUID.randomUUID(), Instant.now(), toolCallId, toolName, content, isError,
                                            details == null ? Map.of() : Map.copyOf(details));
        }
    }

    record System(UUID id, Instant timestamp, String content) implements Message {
        public System(String content) {
            this(UUID.randomUUID(), Instant.now(), content);
        }
    }

    record CompactionSummary(UUID id, Instant timestamp, String summary) implements Message {
        public CompactionSummary(String summary) {
            this(UUID.randomUUID(), Instant.now(), summary);
        }
    }

    record BranchSummary(UUID id, Instant timestamp, String summary) implements Message {
        public BranchSummary(String summary) {
            this(UUID.randomUUID(), Instant.now(), summary);
        }
    }

    record Custom(UUID id, Instant timestamp, String customType, String content,
                                    Map<String, Object> details) implements Message {

        /** Details flag marking an entry as a persisted state snapshot frontends should not render. */
        public static final String SILENT = "silent";

        public Custom(String customType, String content) {
            this(UUID.randomUUID(), Instant.now(), customType, content, Map.of());
        }

        public Custom(String customType, String content, Map<String, Object> details) {
            this(UUID.randomUUID(), Instant.now(), customType, content,
                                            details == null ? Map.of() : copyOfNullable(details));
        }

        /**
         * True when this is a persisted state snapshot (e.g. goal status), kept for reload but shown via a status line,
         * not echoed into the transcript. Read by every frontend so the {@link #SILENT} key lives in one place.
         */
        public boolean isSilent() {
            return details != null && Boolean.TRUE.equals(details.get(SILENT));
        }

        // Map.copyOf rejects null values, but older persisted snapshots can carry one (e.g. a goal entry written as
        // {"json":null} before that key was omitted), so tolerate it so reloading those sessions doesn't throw.
        private static Map<String, Object> copyOfNullable(Map<String, Object> details) {
            var copy = new HashMap<String, Object>(details);
            return Collections.unmodifiableMap(copy);
        }
    }
}
