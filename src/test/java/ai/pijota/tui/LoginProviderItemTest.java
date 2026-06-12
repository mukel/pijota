package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginProviderItemTest {

    @Test
    void storesFields() {
        var item = new LoginProviderItem("openai", "OpenAI", "oauth login", false);
        assertEquals("openai", item.id());
        assertEquals("OpenAI", item.titleText());
        assertEquals("oauth login", item.detailText());
        assertFalse(item.selected());
    }

    @Test
    void searchTextCombinesTitleAndDetail() {
        var item = new LoginProviderItem("id", "Name", "Desc", true);
        assertEquals("Name Desc", item.searchText());
    }

    @Test
    void equalityByFields() {
        assertEquals(new LoginProviderItem("a", "t", "d", true), new LoginProviderItem("a", "t", "d", true));
    }
}
