package org.pijota.tui;

import org.pijota.llm.ModelInfo;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelItemTest {

    @Test
    void storesInfoAndCurrent() {
        var info = new ModelInfo("gpt-4", "openai", "GPT-4", 128_000);
        var item = new ModelItem(info, true);
        assertEquals(info, item.info());
        assertTrue(item.current());
    }

    @Test
    void searchTextIncludesAllFields() {
        var info = new ModelInfo("gpt-4", "openai", "GPT-4", 128_000);
        var item = new ModelItem(info, false);
        assertTrue(item.searchText().contains("gpt-4"));
        assertTrue(item.searchText().contains("openai"));
        assertTrue(item.searchText().contains("GPT-4"));
    }

    @Test
    void titleTextIsId() {
        var item = new ModelItem(new ModelInfo("claude", "anthropic", "Claude", 200_000), false);
        assertEquals("claude", item.titleText());
    }

    @Test
    void detailTextShowsProviderAndContextWindow() {
        var info = new ModelInfo("gpt-4", "openai", "GPT-4", 128_000);
        var item = new ModelItem(info, false);
        assertTrue(item.detailText().contains("openai"));
        assertTrue(item.detailText().contains("context 125K"));
    }

    @Test
    void detailTextShowsCapabilitiesAndPricing() {
        var info = new ModelInfo("vision", "openai", "Vision", 128_000, 1.25, 10.0, List.of("image"));
        var item = new ModelItem(info, false);

        assertTrue(item.detailText().contains("input image,text"), item.detailText());
        assertTrue(item.detailText().contains("price $1.25/$10/MTok"), item.detailText());
    }

    @Test
    void detailTextCarriesNoCurrentMarkerSoTheColumnsStayAligned() {
        // The marker is drawn by SelectorRegion in its own gutter. Baked in here, it pushed this row's provider tag
        // three columns right of every other row's, and each selector spelled the marker differently.
        var current = new ModelItem(new ModelInfo("gpt-4", "openai", "GPT-4", 128_000), true);
        var other = new ModelItem(new ModelInfo("gpt-5", "openai", "GPT-5", 128_000), false);
        assertTrue(current.detailText().startsWith("["), current.detailText());
        assertEquals(other.detailText().indexOf("["), current.detailText().indexOf("["), "same starting column");
        assertTrue(current.selected(), "being current is reported, not drawn");
    }

    @Test
    void selectedReflectsCurrent() {
        assertTrue(new ModelItem(new ModelInfo("a", "b", "c", 1000), true).selected());
        assertFalse(new ModelItem(new ModelInfo("a", "b", "c", 1000), false).selected());
    }
}
