package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsItemTest {

    @Test
    void storesFields() {
        var item = new SettingsItem("id1", "Title", "Detail", true);
        assertEquals("id1", item.id());
        assertEquals("Title", item.titleText());
        assertEquals("Detail", item.detailText());
        assertTrue(item.selected());
    }

    @Test
    void searchTextCombinesTitleAndDetail() {
        var item = new SettingsItem("x", "hello", "world", false);
        assertEquals("hello world", item.searchText());
    }

    @Test
    void equalityByFields() {
        assertEquals(new SettingsItem("a", "t", "d", false), new SettingsItem("a", "t", "d", false));
        assertNotEquals(new SettingsItem("a", "t", "d", false), new SettingsItem("b", "t", "d", false));
    }
}
