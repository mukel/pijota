package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The session file's format, round-tripped in memory.
 *
 * <p>
 * These are pure functions over a parsed entry, so the format can be exercised without a directory, a lock or a branch
 * position. While they lived inside {@link SessionManager} the only way to reach them was to write a file and read it
 * back, which tests the file handling as much as the mapping and says nothing about which of the two broke.
 */
class SessionCodecTest {

    /** What {@code appendMessage} wraps every message in before the codec fills the rest. */
    private static Map<String, Object> entryFor(Message message) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", UUID.randomUUID().toString());
        entry.put("timestamp", Instant.now().toString());
        SessionCodec.serializeMessage(entry, message);
        return entry;
    }

    @Test
    void aUserMessageSurvivesTheRoundTrip() {
        var original = new Message.User("hello there");
        var back = assertInstanceOf(Message.User.class, SessionCodec.deserializeEntry(entryFor(original)));
        assertEquals("hello there", back.content());
    }

    @Test
    void anAssistantMessageKeepsItsThinkingAndItsProvenance() {
        var original = new Message.Assistant(UUID.randomUUID(), Instant.now(), "the answer", List.of(), "the reasoning",
                                        "openai", "gpt-5.6-sol", new Usage(10, 20));
        var back = assertInstanceOf(Message.Assistant.class, SessionCodec.deserializeEntry(entryFor(original)));
        assertEquals("the answer", back.content());
        assertEquals("the reasoning", back.thinking());
        // Which model said it: a resumed session restores the model from this, so losing it silently changes who is
        // answering after a reload.
        assertEquals("openai", back.sourceProvider());
        assertEquals("gpt-5.6-sol", back.sourceModel());
    }

    @Test
    void toolCallsSurviveWithTheirIdsAndArguments() {
        var call = new ToolCall("call_1", "bash", "{\"command\":\"ls\"}");
        var original = new Message.Assistant(UUID.randomUUID(), Instant.now(), "running it", List.of(call), null,
                                        "openai", "gpt-5.6", new Usage(1, 1));
        var back = assertInstanceOf(Message.Assistant.class, SessionCodec.deserializeEntry(entryFor(original)));
        assertEquals(1, back.toolCalls().size());
        // The id is what pairs a result to its call; a provider rejects the next request if the pairing breaks.
        assertEquals("call_1", back.toolCalls().get(0).id());
        assertEquals("bash", back.toolCalls().get(0).name());
        assertEquals("{\"command\":\"ls\"}", back.toolCalls().get(0).arguments());
    }

    @Test
    void aToolResultKeepsWhetherItFailed() {
        var original = new Message.ToolResult("call_1", "bash", "no such file", true);
        var back = assertInstanceOf(Message.ToolResult.class, SessionCodec.deserializeEntry(entryFor(original)));
        assertEquals("call_1", back.toolCallId());
        assertEquals("no such file", back.content());
        assertTrue(back.isError(), "an error that reloads as a success is a lie about what happened");
    }

    /** Bookkeeping is not something anybody said, so it deserialises to nothing at all. */
    @Test
    void everyMetadataTypeDeserialisesToNothing() {
        for (String type : List.of("model_change", "thinking_level_change", "cache_metadata", "session_info",
                                        "compaction", "session", "branch_summary")) {
            var entry = new LinkedHashMap<String, Object>(Map.of("id", UUID.randomUUID().toString(), "type", type));
            assertTrue(SessionCodec.isMetadataEntry(entry), type + " is bookkeeping");
            assertNull(SessionCodec.deserializeEntry(entry), type + " must not become a message");
        }
    }

    @Test
    void aMessageWithNoTypeIsTreatedAsOne() {
        var entry = new LinkedHashMap<String, Object>(Map.of("id", UUID.randomUUID().toString(), "role", "user",
                                        "content", "from an older writer"));
        assertFalse(SessionCodec.isMetadataEntry(entry), "an entry with no type is a message, the original default");
        assertNotNull(SessionCodec.deserializeEntry(entry));
    }

    /**
     * One unreadable field costs the entry, not the conversation. A corrupted timestamp used to throw out of the whole
     * load, so a single bad byte meant a session that would not open.
     */
    @Test
    void anUnreadableIdOrTimestampSkipsTheEntryRatherThanTheFile() {
        var badTimestamp = new LinkedHashMap<String, Object>(Map.of("id", UUID.randomUUID().toString(), "timestamp",
                                        "not-a-timestamp", "role", "user", "content", "x"));
        assertNull(SessionCodec.deserializeEntry(badTimestamp));

        var badId = new LinkedHashMap<String, Object>(Map.of("id", "not-a-uuid", "role", "user", "content", "x"));
        assertNull(SessionCodec.deserializeEntry(badId));
    }

    /**
     * The questions a reader asks of a raw entry. These used to be answered by the tree and its picker, each spelling
     * the keys out itself -- and they had already drifted: one read a missing id as null, the other as "".
     */
    @Test
    void theAccessorsAnswerForEveryReaderOfTheFormat() {
        assertEquals("abc-123", SessionCodec.entryId(Map.of("id", "abc-123")));
        assertNull(SessionCodec.entryId(Map.of("id", 42)), "a non-string id is no id");
        assertNull(SessionCodec.entryId(Map.of()));
        assertEquals("p1", SessionCodec.parentId(Map.of("parentId", "p1")));
        assertNull(SessionCodec.parentId(Map.of()), "no parent means a root");

        assertTrue(SessionCodec.isUserEntry(Map.of("type", "message", "role", "user")));
        assertFalse(SessionCodec.isUserEntry(Map.of("type", "message", "role", "assistant")));
        assertFalse(SessionCodec.isUserEntry(Map.of("type", "tool_result", "role", "")));
        // The earliest writer omitted the type, and those sessions still load: absent means message.
        assertTrue(SessionCodec.isUserEntry(Map.of("role", "user")));
        assertEquals("message", SessionCodec.entryType(Map.of()));

        assertEquals("hi", SessionCodec.entryContent(Map.of("content", "hi")));
        assertNull(SessionCodec.entryContent(Map.of()));
    }
}
