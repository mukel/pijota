package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PromptCompletionStateTest {
    @Test
    void tracksVisibleItemsSelectionAndPrefix() {
        var state = new PromptCompletionState();
        var items = List.of(new CompletionItem("alpha", "a"), new CompletionItem("beta", "b"));

        state.show(items, "", "src/");

        assertTrue(state.active());
        assertEquals(items, state.visibleItems());
        assertEquals(0, state.selectedIndex());
        assertEquals("src/", state.prefix());
        assertEquals("alpha", state.selectedItem().name());

        state.selectNext();

        assertEquals(1, state.selectedIndex());
        assertEquals("beta", state.selectedItem().name());
    }

    @Test
    void pageDownAndPageUpJumpBySeveral() {
        var state = new PromptCompletionState();
        state.show(IntStream.range(0, 12).mapToObj(i -> new CompletionItem("c" + i, "")).toList(), "", "");

        assertEquals(0, state.selectedIndex());
        state.selectPageDown();
        assertEquals(5, state.selectedIndex()); // jumps by the visible-window size, not one row
        state.selectPageUp();
        assertEquals(0, state.selectedIndex());
    }

    @Test
    void filtersAndClearsSelectionState() {
        var state = new PromptCompletionState();
        var items = List.of(new CompletionItem("/session", ""), new CompletionItem("/settings", ""));

        state.filter(items, "/sett");

        assertEquals(0, state.selectedIndex());
        assertEquals(1, state.total());
        assertEquals("/settings", state.selectedItem().name());

        state.clear();

        assertFalse(state.active());
        assertEquals(List.of(), state.visibleItems());
        assertEquals(-1, state.selectedIndex());
        assertEquals("", state.prefix());
    }
}
