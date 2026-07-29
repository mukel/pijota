package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.ArrayList;

public final class SelectorRegion<T extends SelectorItem> implements PromptRegion.Custom {
    private static final int MAX_VISIBLE = Selector.MAX_VISIBLE;
    private final Selector<T> selector;

    public SelectorRegion(Selector<T> selector) {
        this.selector = selector;
    }

    @Override
    public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
        if (!selector.active())
            return Body.EMPTY;

        int sel = selector.selectionIndex();
        int total = selector.total();
        int start = selector.visibleStart();
        int end = start + selector.visibleCount();

        int maxTitle = maxVisibleTitle();
        int detailStart = 2 + maxTitle + 4;
        int detailWidth = Math.max(8, width - detailStart);

        var rows = new ArrayList<String>();
        rows.add(theme.styled("hr", selector.title() + (total > 0 ? "  " + total : "")));

        if (total == 0) {
            rows.add(theme.styled("statusText", selector.itemCount() == 0 ? "  nothing to show" : "  no matches"));
            rows.add(theme.styled("dim", "  " + TuiCopy.SELECTOR_HINT));
            return Body.of(rows);
        }

        if (start > 0)
            rows.add(theme.styled("dim", "  ↑ " + start + " more above"));

        for (int i = start; i < end; i++) {
            var item = selector.visibleItem(i - start);
            String detail = item.detailText();
            if (i == sel) {
                rows.add(theme.styled("prompt", "› ") + theme.styled("heading", item.titleText()) + theme.styled(
                                                "statusText",
                                                " ".repeat(Math.max(0, maxTitle - item.titleText().length() + 4))
                                                                                + AnsiWidth.truncateVisual(detail,
                                                                                                                detailWidth)));
            } else {
                rows.add("  " + theme.styled("statusText", item.titleText()
                                                + " ".repeat(Math.max(0, maxTitle - item.titleText().length() + 4))
                                                + AnsiWidth.truncateVisual(detail, detailWidth)));
            }
        }

        if (start + MAX_VISIBLE < total)
            rows.add(theme.styled("dim", "  ↓ " + (total - start - MAX_VISIBLE) + " more below"));

        rows.add(theme.styled("statusText", "  " + (sel + 1) + "/" + total)
                                        + theme.styled("dim", "  · " + TuiCopy.SELECTOR_HINT));
        return Body.of(rows);
    }

    private int maxVisibleTitle() {
        int start = selector.visibleStart();
        int end = start + selector.visibleCount();
        int max = 0;
        for (int i = start; i < end; i++)
            max = Math.max(max, selector.visibleItem(i - start).titleText().length());
        return max;
    }
}
