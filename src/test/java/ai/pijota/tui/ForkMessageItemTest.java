package ai.pijota.tui;

import ai.pijota.messages.Message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ForkMessageItemTest {

    @Test
    void storesNumberAndMessage() {
        var msg = new Message.User("hello world");
        var item = new ForkMessageItem(5, msg);
        assertEquals(5, item.number());
        assertEquals(msg, item.message());
    }

    @Test
    void searchTextCombinesNumberAndContent() {
        var msg = new Message.User("test query");
        var item = new ForkMessageItem(3, msg);
        assertTrue(item.searchText().contains("3"));
        assertTrue(item.searchText().contains("test query"));
    }

    @Test
    void titleTextIsNumberInBrackets() {
        var item = new ForkMessageItem(7, new Message.User("x"));
        assertEquals("[7]", item.titleText());
    }

    @Test
    void detailTextIsMessageContentTrimmed() {
        var msg = new Message.User("  line1\nline2  ");
        var item = new ForkMessageItem(1, msg);
        var detail = item.detailText();
        assertFalse(detail.contains("\n"));
        assertTrue(detail.contains("line1"));
    }

    @Test
    void equalityByFields() {
        var msg = new Message.User("x");
        assertEquals(new ForkMessageItem(1, msg), new ForkMessageItem(1, msg));
        assertNotEquals(new ForkMessageItem(1, msg), new ForkMessageItem(2, msg));
    }
}
