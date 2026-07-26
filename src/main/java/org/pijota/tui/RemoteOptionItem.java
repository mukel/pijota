package org.pijota.tui;

/** A choice in the {@code /remote-control} selector: {@code web} / {@code telegram} / {@code stop}. */
record RemoteOptionItem(String id, String title, String detail) implements SelectorItem {
    @Override
    public String searchText() {
        return id + " " + title;
    }

    @Override
    public String titleText() {
        return title;
    }

    @Override
    public String detailText() {
        return detail;
    }
}
