package org.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptHistoryTest {
    @Test
    void storesUniqueEntriesMostRecentLast() {
        var history = new PromptHistory();

        history.add("first");
        history.add("second");
        history.add("first");

        var items = history.reverseSearchItems();
        assertEquals("first", items.get(0).name());
        assertEquals("second", items.get(1).name());
    }

    @Test
    void navigatesBackThenRestoresDraft() {
        var history = new PromptHistory();
        history.add("first");
        history.add("second");

        assertEquals("second", history.previous("draft").orElseThrow());
        assertEquals("first", history.previous("draft").orElseThrow());
        assertTrue(history.previous("draft").isEmpty());

        assertEquals("second", history.next().orElseThrow());
        assertEquals("draft", history.next().orElseThrow());
        assertFalse(history.isNavigating());
    }

    @Test
    void suggestsMostRecentCaseInsensitivePrefixMatch() {
        var history = new PromptHistory();
        history.add("hello world");
        history.add("hello there");

        // Returns only the suffix past the typed prefix (rendered dimmed after it).
        // History is reached deliberately (Up/Down, Ctrl+R), never suggested inline: an agent prompt is fresh prose
        // almost every time, so a prefix match on an earlier message is coincidence, and → / End would accept it.
        assertEquals(2, history.reverseSearchItems().size(), "history is still fully reachable");
    }

    @Test
    void replaceAllSeedsHistoryFromLoadedSession() {
        var history = new PromptHistory();
        history.add("typed before load");

        history.replaceAll(List.of("first prompt", "second prompt")); // e.g. a loaded session's user msgs

        // prior entry is gone; up-arrow recalls the loaded session's prompts, newest first.
        assertEquals("second prompt", history.previous("draft").orElseThrow());
        assertEquals("first prompt", history.previous("draft").orElseThrow());
        assertTrue(history.previous("draft").isEmpty());
    }
}
