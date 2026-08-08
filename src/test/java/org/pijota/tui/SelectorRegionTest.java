package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SelectorRegionTest {

    private static final AnsiTheme THEME = AnsiTheme.dark();
    private static final PromptSnapshot SNAPSHOT = new PromptSnapshot(List.of(""), 0, 0, List.of());

    private static PromptRegion.Body compose(SelectorRegion<?> region) {
        return region.compose(80, THEME, SNAPSHOT);
    }

    /** An item that is "the active one" -- the current model, the current theme, the current tree node. */
    private record MarkedItem(String title, String detail, boolean active) implements SelectorItem {
        @Override
        public String searchText() {
            return title;
        }

        @Override
        public String titleText() {
            return title;
        }

        @Override
        public String detailText() {
            return detail;
        }

        @Override
        public boolean selected() {
            return active;
        }
    }

    @Test
    void theActiveItemIsMarkedWithoutPushingItsDetailOutOfTheColumn() {
        // The marker used to live inside each item's own detail text, so the marked row's detail started three
        // columns further right than every other row's and the metadata no longer lined up down the list.
        var items = List.<SelectorItem>of(new MarkedItem("short", "[a]  meta", false),
                                        new MarkedItem("a-much-longer-name", "[b]  meta", true),
                                        new MarkedItem("mid-name", "[c]  meta", false));
        var selector = new Selector<>(items);
        selector.activate("");
        var rows = compose(new SelectorRegion<>(selector)).rows().stream().map(AnsiStripper::strip).toList();

        var detailColumns = rows.stream().filter(r -> r.contains("meta")).map(r -> r.indexOf("[")).toList();
        assertEquals(3, detailColumns.size(), rows.toString());
        assertEquals(1, Set.copyOf(detailColumns).size(), "details must share one column: " + rows);

        String marked = rows.stream().filter(r -> r.contains("a-much-longer-name")).findFirst().orElseThrow();
        assertTrue(marked.contains("●"), "the active item is marked: " + marked);
        assertEquals(1, rows.stream().filter(r -> r.contains("●")).count(), "only the active one: " + rows);
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
    void highlightMarksMatchedCharactersAndPreservesVisibleText() {
        String marker = THEME.style("selectorMatch");
        assertFalse(marker.isEmpty(), "the marker must resolve to a real escape, or every assertion below is vacuous");

        String out = SelectorRegion.highlight(THEME, "model", "heading", new int[] {1, 2, 3});
        assertEquals("model", AnsiStripper.strip(out), "no visible characters added or lost");
        assertTrue(out.contains(marker), "the matched run is marked");
        // The row's own style is kept underneath, so a match never reads duller than the text around it.
        assertTrue(out.contains(THEME.style("heading")), "the base style survives on matched runs");

        String plain = SelectorRegion.highlight(THEME, "model", "heading", new int[0]);
        assertEquals(THEME.styled("heading", "model"), plain, "no match → plain base style, no marker");
        assertFalse(plain.contains(marker), "unmatched text carries no marker");
    }

    @Test
    void theMatchMarkerReadsTheSameOnSelectedAndUnselectedRows() {
        // The selected row is bold+accent, unselected rows are dim. A marker that REPLACED the base style (the old
        // "pure bold") dropped the accent and vanished into the selected row; an attribute composes with either.
        String marker = THEME.style("selectorMatch");
        assertFalse(marker.isEmpty(), "guard against a vacuous contains(\"\")");

        String onSelected = SelectorRegion.highlight(THEME, "model", "heading", new int[] {0, 1});
        String onMuted = SelectorRegion.highlight(THEME, "model", "statusText", new int[] {0, 1});
        assertTrue(onSelected.contains(marker) && onMuted.contains(marker), "both rows mark matches the same way");
        assertTrue(onSelected.contains(THEME.style("heading")), "selected keeps its accent: " + onSelected);
        assertTrue(onMuted.contains(THEME.style("statusText")), "muted keeps its dim: " + onMuted);
    }

    @Test
    void activeSelectorRowHighlightsTheTypedQuery() {
        var selector = new Selector<>(List.<SelectorItem>of(new CompletionItem("model", "switch model")));
        selector.activate("ode"); // subsequence/substring of "model"
        var region = new SelectorRegion<>(selector);
        String row = compose(region).rows().stream().filter(r -> AnsiStripper.strip(r).contains("model")).findFirst()
                                        .orElseThrow();
        assertTrue(row.contains(THEME.style("selectorMatch")), "the matched run is marked in the rendered row: " + row);
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
    void slotSpecificActionAppearsInTheFooterAheadOfTypeToSearch() {
        // The sessions picker deletes with Ctrl+D, but the footer only named the generic chips; the one action the
        // picker adds was invisible unless you had already read the status line or /help.
        var selector = new Selector<>(List.<SelectorItem>of(new CompletionItem("alpha", "desc A")));
        selector.setExtraHint("Ctrl+D delete");
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        String footer = compose(region).rows().getLast();
        assertTrue(footer.contains("Ctrl+D delete"), AnsiStripper.strip(footer));
        String plain = AnsiStripper.strip(footer);
        assertTrue(plain.indexOf("Ctrl+D delete") < plain.indexOf("type to search"),
                                        "the extra chip goes before the most droppable one: " + plain);
    }

    @Test
    void blankExtraHintKeepsTheGenericFooter() {
        var selector = new Selector<>(List.<SelectorItem>of(new CompletionItem("alpha", "desc A")));
        selector.setExtraHint(" ");
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        String plain = AnsiStripper.strip(compose(region).rows().getLast());
        assertTrue(plain.contains("type to search"), plain);
        assertFalse(plain.contains("  ·  · "), "no empty chip: " + plain);
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
