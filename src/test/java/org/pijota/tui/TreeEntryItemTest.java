package org.pijota.tui;

import org.junit.jupiter.api.Test;

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

    @Test
    void fromRendersRoleAndEllipsizedContent() {
        var item = TreeEntryItem.from(Map.of("id", "x1", "type", "message", "role", "user",
                                        "content", "hello\nworld"), true);
        assertEquals("user", item.role());
        assertEquals("hello world", item.content(), "newlines are flattened for a single-line row");
        assertTrue(item.titleText().startsWith("→ "), "the current entry is marked");
    }
}
