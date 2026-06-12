package ai.pijota.tui;

interface SelectorItem {
    String searchText();

    String titleText();

    String detailText();

    default boolean selected() {
        return false;
    }
}
