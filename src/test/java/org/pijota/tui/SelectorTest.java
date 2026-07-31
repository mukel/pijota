package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectorTest {
    private static final SelectorItem A = item("A", "A item");
    private static final SelectorItem B = item("B", "B item");
    private static final SelectorItem C = item("C", "C item");

    private static SelectorItem item(String name, String search) {
        return new SelectorItem() {
            @Override
            public String searchText() {
                return search;
            }

            @Override
            public String titleText() {
                return name;
            }

            @Override
            public String detailText() {
                return "";
            }
        };
    }

    @Test
    void activationFiltersItemsByFuzzyMatch() {
        var s = new Selector<>(List.of(A, B, C));
        s.activate("a");

        assertEquals(1, s.total());
        assertEquals("A", s.selectedItem().titleText());
    }

    @Test
    void blankOrNullQueryShowsAllItems() {
        var s = new Selector<>(List.of(A, B, C));
        s.activate("");

        assertEquals(3, s.total());
    }

    @Test
    void ranksSubstringMatchesAboveScatteredSubsequences() {
        var scattered = item("scattered", "xaxbxc"); // "abc" only as a scattered subsequence
        var substring = item("substring", "zabc");    // "abc" as a contiguous substring
        var s = new Selector<>(List.of(scattered, substring)); // scattered listed first
        s.activate("abc");
        assertEquals("substring", s.selectedItem().titleText(),
                                        "a substring match must outrank a scattered subsequence, not defer to list order");
    }

    @Test
    void refreshItemsKeepsTheSelectionUnlikeSetItems() {
        // Repro for "/settings scrolls to top": cycling a setting value refreshes the list; the cursor must stay put.
        var s = new Selector<>(List.of(A, B, C));
        s.activate("");
        s.selectNext();
        s.selectNext(); // selected = 2 (C)

        s.refreshItems(List.of(A, B, C)); // same rows, a value changed in place
        assertEquals(2, s.selectionIndex(), "refresh keeps the cursor");

        s.refreshItems(List.of(A, B)); // list shrank → clamp to last
        assertEquals(1, s.selectionIndex());

        s.setItems(List.of(A, B, C)); // a fresh open still resets to top
        assertEquals(0, s.selectionIndex());
    }

    @Test
    void filteringBackFromNoMatchesResetsSelectionFromNegative() {
        // Repro for "Index -1 out of bounds": a no-match search sets selected=-1; when a later query matches again,
        // selected must snap back to a valid index rather than stay -1 (which crashed selectedItem() every keypress).
        var s = new Selector<>(List.of(A, B, C));
        s.activate("");
        s.filter("zzz"); // no matches
        assertEquals(-1, s.selectionIndex());
        s.filter("a"); // matches A again
        assertEquals(0, s.selectionIndex());
        assertDoesNotThrow(s::selectedItem);
        assertEquals("A", s.selectedItem().titleText());
    }

    @Test
    void filteringJumpsHighlightToTopMatch() {
        // A fresh query highlights the best (first) match, not a stale cursor from the previous query — else Enter
        // would select whatever row the cursor happened to sit on before the user started typing.
        var s = new Selector<>(List.of(A, B, C));
        s.activate("");
        s.selectNext();
        s.selectNext(); // cursor on C

        s.filter("b"); // narrow to B
        assertEquals(0, s.selectionIndex(), "cursor snaps to the top match");
        assertEquals("B", s.selectedItem().titleText());
    }

    @Test
    void selectNextAndPrevCycleThroughFilteredItems() {
        var s = new Selector<>(List.of(A, B, C));
        s.activate("");

        assertEquals(0, s.selectionIndex());
        s.selectNext();
        assertEquals(1, s.selectionIndex());
        s.selectNext();
        assertEquals(2, s.selectionIndex());
        s.selectNext();
        assertEquals(0, s.selectionIndex());

        s.selectPrev();
        assertEquals(2, s.selectionIndex());
    }

    @Test
    void emptyFilterShowsZeroItems() {
        var s = new Selector<>(List.of(A, B, C));
        s.activate("xyz");

        assertEquals(0, s.total());
        assertEquals(-1, s.selectionIndex());
    }

    @Test
    void selectFirstFindsMatchingItem() {
        var s = new Selector<>(List.of(A, B, C));
        s.activate("");

        s.selectFirst(i -> i.titleText().equals("B"));

        assertEquals("B", s.selectedItem().titleText());
    }
}
