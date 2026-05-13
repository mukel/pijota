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

    record Assistant(
        UUID id, Instant timestamp,
        String content,
        List<ToolCall> toolCalls,
        String thinking
    ) implements Message {
        public Assistant(String content, List<ToolCall> toolCalls, String thinking) {
            this(UUID.randomUUID(), Instant.now(), content, toolCalls, thinking);
        }
    }

    record ToolResult(
        UUID id, Instant timestamp,
        String toolCallId,
        String toolName,
        String content,
        boolean isError,
        Map<String, Object> details
    ) implements Message {
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
}
