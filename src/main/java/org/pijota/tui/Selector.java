package org.pijota.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

final class Selector<T extends SelectorItem> {
    static final int MAX_VISIBLE = 5;

    private List<T> allItems;
    private final List<Integer> visibleIndices = new ArrayList<>();
    private int selected;
    private boolean active;
    private String title = "select";
    private String query = ""; // the active filter, kept so the renderer can highlight matched characters
    private String extraHint = ""; // a slot-specific footer chip (e.g. the sessions picker's "Ctrl+X delete")

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

    /**
     * Replaces the items but keeps the current selection (clamped), for a live refresh of the same rows with changed
     * content, e.g. cycling a setting value in place. Unlike {@link #setItems}, which resets to the top.
     */
    void refreshItems(List<T> items) {
        int keep = selected;
        setItems(items);
        if (!visibleIndices.isEmpty())
            selected = Math.max(0, Math.min(keep, visibleIndices.size() - 1));
    }

    void setTitle(String title) {
        this.title = title == null || title.isBlank() ? "select" : title;
    }

    String title() {
        return title;
    }

    void setExtraHint(String extraHint) {
        this.extraHint = extraHint == null ? "" : extraHint;
    }

    String extraHint() {
        return extraHint;
    }

    String query() {
        return query;
    }

    void activate(String query) {
        filter(query);
        active = true;
        selected = visibleIndices.isEmpty() ? -1 : 0;
    }

    void selectFirst(Predicate<T> test) {
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
        this.query = query == null ? "" : query;
        visibleIndices.clear();
        if (query == null || query.isBlank()) {
            for (int i = 0; i < allItems.size(); i++)
                visibleIndices.add(i);
        } else {
            String q = query.toLowerCase();
            // score each item once (Schwartzian) so the sort is a cheap array lookup, not a matchScore + toLowerCase
            // recompute per comparison; this runs on every keystroke over the whole (e.g. ~400-model) list.
            int[] scoreByIndex = new int[allItems.size()];
            boolean hasContiguousMatch = false;
            for (int i = 0; i < allItems.size(); i++) {
                String searchText = allItems.get(i).searchText().toLowerCase();
                if (SlashCommandRegistry.fuzzyMatch(q, searchText)) {
                    scoreByIndex[i] = SlashCommandRegistry.matchScore(q, searchText);
                    visibleIndices.add(i);
                    hasContiguousMatch |= searchText.contains(q);
                }
            }
            if (hasContiguousMatch)
                visibleIndices.removeIf(i -> !allItems.get(i).searchText().toLowerCase().contains(q));
            // rank best matches first: prefix beats substring beats a scattered subsequence. A stable sort keeps
            // equal-scoring items in their original order (e.g. the configured model/theme order), so "pro" surfaces
            // "deepseek-v4-pro" (substring) above a scattered match instead of leaving list order untouched.
            visibleIndices.sort(Comparator.comparingInt(i -> scoreByIndex[i]));
        }
        // A fresh query highlights the top match (index 0), like any fuzzy finder, so Enter takes the best result,
        // not a stale mid-list cursor from the previous query. -1 when nothing matches (selectedItem() guards on it).
        selected = visibleIndices.isEmpty() ? -1 : 0;
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
