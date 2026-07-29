package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectorRegionTest {

    private static final AnsiTheme THEME = AnsiTheme.dark();
    private static final PromptSnapshot SNAPSHOT = new PromptSnapshot(List.of(""), 0, 0, List.of());

    private static PromptRegion.Body compose(SelectorRegion<?> region) {
        return region.compose(80, THEME, SNAPSHOT);
    }

    @Test
    void emptyBodyWhenInactive() {
        var selector = new Selector<>(List.of());
        var region = new SelectorRegion<>(selector);
        var body = compose(region);
        assertTrue(body.rows().isEmpty());
        assertEquals(-1, body.cursorRow(), "an inactive selector claims no caret");
    }

    @Test
    void composesRowsWhenActiveWithItems() {
        var items = List.<SelectorItem>of(new CompletionItem("alpha", "desc A"), new CompletionItem("beta", "desc B"),
                                        new CompletionItem("gamma", "desc C"));
        var selector = new Selector<>(items);
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        assertFalse(compose(region).rows().isEmpty(), "rows should be composed when active with items");
    }

    @Test
    void composesRowsWhenActiveWithNoItems() {
        var selector = new Selector<>(List.of());
        selector.activate("");
        var region = new SelectorRegion<>(selector);
        assertFalse(compose(region).rows().isEmpty());
    }

    @Test
    void composesRowsWithManyItems() {
        var items = new ArrayList<SelectorItem>();
        for (int i = 0; i < 20; i++)
            items.add(new CompletionItem("item" + i, "desc" + i));
        var selector = new Selector<>(items);
        selector.activate("");
        var region = new SelectorRegion<>(selector);
        assertFalse(compose(region).rows().isEmpty());
    }

    @Test
    void activeWithItemsShowsTitleAndItems() {
        var items = List.<SelectorItem>of(new CompletionItem("alpha", "desc A"), new CompletionItem("beta", "desc B"));
        var selector = new Selector<>(items);
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        var rendered = String.join("\n", compose(region).rows());

        assertTrue(rendered.contains("select"), rendered);
        assertTrue(rendered.contains("alpha"), rendered);
        assertTrue(rendered.contains("beta"), rendered);
        assertTrue(rendered.contains("1/2"), rendered);
        assertTrue(rendered.contains("type to search"), rendered);
    }

    @Test
    void activeWithNoItemsShowsNothingToShow() {
        var selector = new Selector<>(List.of());
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        var rendered = String.join("\n", compose(region).rows());

        assertTrue(rendered.contains("nothing to show"), rendered);
    }

    @Test
    void scrolledListShowsBothScrollIndicators() {
        var items = new ArrayList<SelectorItem>();
        for (int i = 0; i < 12; i++)
            items.add(new CompletionItem("item" + i, "desc" + i));
        var selector = new Selector<>(items);
        selector.activate("");
        for (int i = 0; i < 5; i++)
            selector.selectNext();

        var region = new SelectorRegion<>(selector);
        var body = compose(region);

        assertTrue(body.rows().size() > 5);
        var rendered = String.join("\n", body.rows());
        assertTrue(rendered.contains("↑"), rendered);
        assertTrue(rendered.contains("↓"), rendered);
    }
}
