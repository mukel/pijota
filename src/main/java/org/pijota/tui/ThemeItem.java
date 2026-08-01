package org.pijota.tui;

record ThemeItem(String name, String path, boolean current) implements SelectorItem {
    @Override
    public String searchText() {
        return name;
    }

    @Override
    public String titleText() {
        return name;
    }

    @Override
    public String detailText() {
        return path;
    }

    @Override
    public boolean selected() {
        return current;
    }
}
