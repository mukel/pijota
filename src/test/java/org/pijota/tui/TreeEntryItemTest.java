package org.pijota.tui;

import org.pijota.agent.SessionCodec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TreeEntryItemTest {
    @Test
    void internalStateEntriesAreHiddenFromTheTree() {
        // Pure bookkeeping entries have no content and must not clutter the conversation tree (they used to render
        // as "cache_metadata cache_metadata" and were meaningless jump targets).
        assertTrue(TreeEntryItem.isInternalMetadata(Map.of("type", "cache_metadata")));
        assertTrue(TreeEntryItem.isInternalMetadata(Map.of("type", "model_change")));
        assertTrue(TreeEntryItem.isInternalMetadata(Map.of("type", "thinking_level_change")));
        assertTrue(TreeEntryItem.isInternalMetadata(Map.of("type", "session_info")));
    }

    @Test
    void conversationAndContentEntriesAreKept() {
        assertFalse(TreeEntryItem.isInternalMetadata(Map.of("type", "message")));
        assertFalse(TreeEntryItem.isInternalMetadata(Map.of("type", "branch_summary")));
        assertFalse(TreeEntryItem.isInternalMetadata(Map.of("type", "compaction")));
        assertFalse(TreeEntryItem.isInternalMetadata(Map.of()), "a missing type defaults to a message and is kept");
    }

    /**
     * The two "is this metadata" predicates answer different questions and must keep disagreeing. Unifying them is an
     * easy-looking cleanup that quietly stops the fork picker offering the compaction point of a long conversation --
     * one of the likelier places to want to branch from.
     */
    @Test
    void theForkPickerKeepsWhatTheCodecCallsMetadata() {
        for (String type : List.of("compaction", "branch_summary")) {
            var entry = Map.<String, Object>of("type", type);
            assertTrue(SessionCodec.isMetadataEntry(entry), type + " becomes no message");
            assertFalse(TreeEntryItem.isInternalMetadata(entry), type + " is still a place you can fork from");
        }
        // And where they do agree, they agree: pure bookkeeping is hidden by both.
        for (String type : List.of("cache_metadata", "model_change", "thinking_level_change", "session_info")) {
            var entry = Map.<String, Object>of("type", type);
            assertTrue(SessionCodec.isMetadataEntry(entry), type);
            assertTrue(TreeEntryItem.isInternalMetadata(entry), type);
        }
    }

    @Test
    void fromRendersRoleAndEllipsizedContent() {
        var item = TreeEntryItem.from(Map.of("id", "x1", "type", "message", "role", "user", "content", "hello\nworld"),
                                        true);
        assertEquals("user", item.role());
        assertEquals("hello world", item.content(), "newlines are flattened for a single-line row");
        assertEquals("user", item.titleText(), "no marker baked into the title; the selector draws it");
        assertTrue(item.selected(), "the current entry reports itself as selected");
    }
}
