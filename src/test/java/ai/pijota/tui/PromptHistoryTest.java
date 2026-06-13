package ai.pijota.tui;

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
        assertEquals("lo there", history.suggestionFor("HEL"));
        assertEquals("", history.suggestionFor("missing"));
    }
}
