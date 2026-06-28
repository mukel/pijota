package ai.pijota.messages;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void userMessageHasIdAndTimestamp() {
        var msg = new Message.User("hello");
        assertNotNull(msg.id());
        assertNotNull(msg.timestamp());
        assertEquals("hello", msg.content());
    }

    @Test
    void userMessageFullConstructor() {
        var id = UUID.randomUUID();
        var ts = Instant.now();
        var msg = new Message.User(id, ts, "hi", List.of());
        assertEquals(id, msg.id());
        assertEquals(ts, msg.timestamp());
    }

    @Test
    void assistantMessageStoresContentAndThinking() {
        var msg = new Message.Assistant("response", List.of(), "thinking...");
        assertEquals("response", msg.content());
        assertEquals("thinking...", msg.thinking());
        assertTrue(msg.toolCalls().isEmpty());
    }

    @Test
    void assistantWithSourcePreservesFields() {
        var msg = new Message.Assistant("text", List.of(), "think");
        var sourced = msg.withSource("openai", "gpt-4");
        assertEquals("text", sourced.content());
        assertEquals("openai", sourced.sourceProvider());
        assertEquals("gpt-4", sourced.sourceModel());
    }

    @Test
    void assistantWithThinkingPreservesOtherFields() {
        var msg = new Message.Assistant("text", List.of(), "old");
        var updated = msg.withThinking("new");
        assertEquals("text", updated.content());
        assertEquals("new", updated.thinking());
        assertEquals(msg.sourceProvider(), updated.sourceProvider());
    }

    @Test
    void toolResultStoresCallMetadata() {
        var msg = new Message.ToolResult("call-1", "read", "file contents", false);
        assertEquals("call-1", msg.toolCallId());
        assertEquals("read", msg.toolName());
        assertEquals("file contents", msg.content());
        assertFalse(msg.isError());
    }

    @Test
    void toolResultNullDetailsCoercesToEmpty() {
        var msg = new Message.ToolResult("call-1", "read", "ok", false, null);
        assertTrue(msg.details().isEmpty());
    }

    @Test
    void toolResultWithDetails() {
        var msg = new Message.ToolResult("call-1", "bash", "output", true, Map.of("exitCode", 1));
        assertTrue(msg.isError());
        assertEquals(Map.of("exitCode", 1), msg.details());
    }

    @Test
    void systemMessageStoresContent() {
        var msg = new Message.System("You are a helpful assistant");
        assertEquals("You are a helpful assistant", msg.content());
    }

    @Test
    void compactionSummaryStoresSummary() {
        var msg = new Message.CompactionSummary("summarized context");
        assertEquals("summarized context", msg.summary());
    }

    @Test
    void branchSummaryStoresSummary() {
        var msg = new Message.BranchSummary("branch content");
        assertEquals("branch content", msg.summary());
    }

    @Test
    void customMessageStoresTypeAndContent() {
        var msg = new Message.Custom("notification", "hello");
        assertEquals("notification", msg.customType());
        assertEquals("hello", msg.content());
    }

    @Test
    void customMessageNullDetailsCoercesToEmpty() {
        var msg = new Message.Custom("type", "content", null);
        assertTrue(msg.details().isEmpty());
    }

    @Test
    void customMessageWithDetails() {
        var msg = new Message.Custom("type", "content", Map.of("key", "value"));
        assertEquals(Map.of("key", "value"), msg.details());
    }

    @Test
    void attachmentsDefaultToEmptyAndDefensivelyCopied() {
        assertTrue(new Message.User("hi").attachments().isEmpty(), "1-arg ctor → no attachments");
        assertTrue(new Message.User("hi", null).attachments().isEmpty(), "null attachments → empty list");

        var mutable = new ArrayList<Message.Attachment>(List.of(new Message.Attachment("image/png", "QQ==", "x.png")));
        var msg = new Message.User("look", mutable);
        mutable.clear(); // the message must keep its own copy
        assertEquals(1, msg.attachments().size());
        assertEquals("x.png", msg.attachments().getFirst().filename());
    }

    @Test
    void attachmentApproxBytesAndCap() {
        assertEquals(0, new Message.Attachment("image/png", null, "x").approxBytes(), "null base64 → 0");
        assertEquals(3, new Message.Attachment("image/png", "AAAA", "x").approxBytes(), "4 base64 chars ≈ 3 bytes");
        assertEquals(5L * 1024 * 1024, Message.Attachment.MAX_BYTES);
    }

    @Test
    void sealedHierarchyCoversAllCases() {
        assertTrue(new Message.User("x") instanceof Message);
        assertTrue(new Message.Assistant("x", List.of(), "t") instanceof Message);
        assertTrue(new Message.ToolResult("id", "name", "c", false) instanceof Message);
        assertTrue(new Message.System("x") instanceof Message);
        assertTrue(new Message.CompactionSummary("x") instanceof Message);
        assertTrue(new Message.BranchSummary("x") instanceof Message);
        assertTrue(new Message.Custom("t", "x") instanceof Message);
    }
}
