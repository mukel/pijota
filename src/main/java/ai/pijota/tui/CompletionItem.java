package ai.pijota.tui;

record CompletionItem(String name, String description) implements SelectorItem {
    @Override public String searchText() { return name; }
    @Override public String titleText() { return name; }
    @Override public String detailText() { return description; }
}