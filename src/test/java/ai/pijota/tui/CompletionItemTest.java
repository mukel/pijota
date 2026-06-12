package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompletionItemTest {

    @Test
    void storesNameAndDescription() {
        var item = new CompletionItem("file.txt", "a text file");
        assertEquals("file.txt", item.name());
        assertEquals("a text file", item.description());
    }

    @Test
    void implementsSelectorItem() {
        var item = new CompletionItem("test", "desc");
        assertEquals("test", item.searchText());
        assertEquals("test", item.titleText());
        assertEquals("desc", item.detailText());
        assertFalse(item.selected());
    }

    @Test
    void equalityByFields() {
        assertEquals(new CompletionItem("a", "d"), new CompletionItem("a", "d"));
        assertNotEquals(new CompletionItem("a", "d"), new CompletionItem("b", "d"));
    }
}
