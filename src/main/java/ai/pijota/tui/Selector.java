package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;

final class Selector<T extends SelectorItem> {
    static final int MAX_VISIBLE = 5;

    private List<T> allItems;
    private final List<Integer> visibleIndices = new ArrayList<>();
    private int selected;
    private boolean active;
    private String title = "select";

    Selector(List<T> items) {
        allItems = List.copyOf(items);
    }

    void setItems(List<T> items) {
        allItems = List.copyOf(items);
        visibleIndices.clear();
        for (int i = 0; i < allItems.size(); i++)
            visibleIndices.add(i);
        selected = 0;
    }

    void setTitle(String title) {
        this.title = title == null || title.isBlank() ? "select" : title;
    }

    String title() {
        return title;
    }

    void activate(String query) {
        filter(query);
        active = true;
        selected = visibleIndices.isEmpty() ? -1 : 0;
    }

    void selectFirst(java.util.function.Predicate<T> test) {
        for (int i = 0; i < visibleIndices.size(); i++) {
            if (test.test(allItems.get(visibleIndices.get(i)))) {
                selected = i;
                return;
            }
        }
    }

    void deactivate() {
        active = false;
    }

    boolean active() {
        return active;
    }

    void filter(String query) {
        visibleIndices.clear();
        if (query == null || query.isBlank()) {
            for (int i = 0; i < allItems.size(); i++)
                visibleIndices.add(i);
        } else {
            String q = query.toLowerCase();
            for (int i = 0; i < allItems.size(); i++) {
                if (SlashCommandRegistry.fuzzyMatch(q, allItems.get(i).searchText().toLowerCase()))
                    visibleIndices.add(i);
            }
        }
        if (visibleIndices.isEmpty()) {
            selected = -1;
            return;
        }
        if (selected >= visibleIndices.size())
            selected = Math.max(0, visibleIndices.size() - 1);
    }

    void selectNext() {
        if (visibleIndices.isEmpty())
            return;
        selected = (selected + 1) % visibleIndices.size();
    }

    void selectPrev() {
        if (visibleIndices.isEmpty())
            return;
        selected = selected <= 0 ? visibleIndices.size() - 1 : selected - 1;
    }

    void selectPageUp() {
        if (visibleIndices.isEmpty())
            return;
        selected = Math.max(0, selected - MAX_VISIBLE);
    }

    void selectPageDown() {
        if (visibleIndices.isEmpty())
            return;
        selected = Math.min(visibleIndices.size() - 1, selected + MAX_VISIBLE);
    }

    int visibleCount() {
        return Math.min(MAX_VISIBLE, visibleIndices.size());
    }

    int total() {
        return visibleIndices.size();
    }

    int itemCount() {
        return allItems.size();
    }

    int visibleStart() {
        if (visibleIndices.size() <= MAX_VISIBLE)
            return 0;
        int half = MAX_VISIBLE / 2;
        int start = selected - half;
        return Math.max(0, Math.min(start, visibleIndices.size() - MAX_VISIBLE));
    }

    T selectedItem() {
        return allItems.get(visibleIndices.get(selected));
    }

    T visibleItem(int position) {
        return allItems.get(visibleIndices.get(visibleStart() + position));
    }

    int selectionIndex() {
        return selected;
    }
}
