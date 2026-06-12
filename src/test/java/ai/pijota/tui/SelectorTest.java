package ai.pijota.tui;

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
