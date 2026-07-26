package org.pijota.tui;

import java.util.List;

final class PromptCompletionState {
    private final Selector<CompletionItem> selector = new Selector<>(List.of());
    private List<CompletionItem> visibleItems = List.of();
    private int selectedIndex = -1;
    private String prefix = "";

    List<CompletionItem> visibleItems() {
        return visibleItems;
    }

    int selectedIndex() {
        return selectedIndex;
    }

    String prefix() {
        return prefix;
    }

    void prefix(String prefix) {
        this.prefix = prefix != null ? prefix : "";
    }

    boolean active() {
        return selector.active();
    }

    int total() {
        return selector.total();
    }

    int itemCount() {
        return selector.itemCount();
    }

    CompletionItem selectedItem() {
        return selector.selectedItem();
    }

    void show(List<CompletionItem> items, String filter, String prefix) {
        this.prefix = prefix != null ? prefix : "";
        selector.setItems(items != null ? items : List.of());
        selector.activate(filter != null ? filter : "");
        visibleItems = items != null ? items : List.of();
        selectedIndex = selector.selectionIndex();
    }

    void filter(List<CompletionItem> items, String filter) {
        selector.setItems(items != null ? items : List.of());
        selector.filter(filter != null ? filter : "");
        visibleItems = items != null ? items : List.of();
        selectedIndex = selector.selectionIndex();
    }

    void selectNext() {
        selector.selectNext();
        selectedIndex = selector.selectionIndex();
    }

    void selectPrev() {
        selector.selectPrev();
        selectedIndex = selector.selectionIndex();
    }

    void clear() {
        visibleItems = List.of();
        selectedIndex = -1;
        prefix = "";
        selector.setItems(List.of());
        selector.deactivate();
    }
}
