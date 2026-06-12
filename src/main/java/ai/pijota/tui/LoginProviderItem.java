package ai.pijota.tui;

record LoginProviderItem(String id, String titleText, String detailText, boolean selected) implements SelectorItem {
    @Override
    public String searchText() {
        return titleText + " " + detailText;
    }
}
