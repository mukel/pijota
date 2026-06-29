package ai.pijota.tui;

import ai.pijota.llm.ModelInfo;

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
        var info = new ModelInfo("vision", "openai", "Vision", 128_000, 1.25, 10.0, java.util.List.of("image"));
        var item = new ModelItem(info, false);

        assertTrue(item.detailText().contains("input image,text"), item.detailText());
        assertTrue(item.detailText().contains("price $1.25/$10/MTok"), item.detailText());
    }

    @Test
    void detailTextShowsStarWhenCurrent() {
        var info = new ModelInfo("gpt-4", "openai", "GPT-4", 128_000);
        var item = new ModelItem(info, true);
        assertTrue(item.detailText().startsWith("*"));
    }

    @Test
    void selectedReflectsCurrent() {
        assertTrue(new ModelItem(new ModelInfo("a", "b", "c", 1000), true).selected());
        assertFalse(new ModelItem(new ModelInfo("a", "b", "c", 1000), false).selected());
    }
}
