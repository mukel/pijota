package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThemeItemTest {

    @Test
    void storesFields() {
        var item = new ThemeItem("dark", "dark.json", true);
        assertEquals("dark", item.name());
        assertEquals("dark.json", item.path());
        assertTrue(item.current());
    }

    @Test
    void selectedReflectsCurrent() {
        assertTrue(new ThemeItem("x", "y", true).selected());
        assertFalse(new ThemeItem("x", "y", false).selected());
    }

    @Test
    void detailTextShowsStarWhenCurrent() {
        var item = new ThemeItem("dark", "/path/dark.json", true);
        assertEquals("*", item.detailText());
    }

    @Test
    void detailTextShowsPathWhenNotCurrent() {
        var item = new ThemeItem("light", "/path/light.json", false);
        assertEquals("/path/light.json", item.detailText());
    }

    @Test
    void searchAndTitleUseName() {
        var item = new ThemeItem("theme1", "path", false);
        assertEquals("theme1", item.searchText());
        assertEquals("theme1", item.titleText());
    }
}
