package ai.pijota.messages;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface Message {
    UUID id();

    Instant timestamp();

    record User(UUID id, Instant timestamp, String content) implements Message {
        public User(String content) {
            this(UUID.randomUUID(), Instant.now(), content);
        }
    }

    record Assistant(UUID id, Instant timestamp, String content, List<ToolCall> toolCalls, String thinking,
                                    String sourceProvider, String sourceModel) implements Message {
        public Assistant(String content, List<ToolCall> toolCalls, String thinking) {
            this(UUID.randomUUID(), Instant.now(), content, toolCalls, thinking, null, null);
        }

        public Assistant(UUID id, Instant timestamp, String content, List<ToolCall> toolCalls, String thinking) {
            this(id, timestamp, content, toolCalls, thinking, null, null);
        }

        public Assistant withSource(String provider, String model) {
            return new Assistant(id, timestamp, content, toolCalls, thinking, provider, model);
        }

        public Assistant withThinking(String newThinking) {
            return new Assistant(id, timestamp, content, toolCalls, newThinking, sourceProvider, sourceModel);
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
        public Custom(String customType, String content) {
            this(UUID.randomUUID(), Instant.now(), customType, content, Map.of());
        }

        public Custom(String customType, String content, Map<String, Object> details) {
            this(UUID.randomUUID(), Instant.now(), customType, content,
                                            details == null ? Map.of() : Map.copyOf(details));
        }
    }
}
