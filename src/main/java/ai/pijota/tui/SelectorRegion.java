package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.PrintWriter;

public final class SelectorRegion<T extends SelectorItem> implements PromptRegion.Custom {
    private final Selector<T> selector;

    public SelectorRegion(Selector<T> selector) {
        this.selector = selector;
    }

    @Override
    public int height(int width, PromptSnapshot ctx) {
        if (!selector.active() || selector.total() == 0) return 0;
        return 1 + selector.visibleCount() + 1;
    }

    @Override
    public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
        if (!selector.active() || selector.total() == 0) return NO_CURSOR;

        int sel = selector.selectionIndex();
        int total = selector.total();
        int start = selector.visibleStart();
        int end = start + selector.visibleCount();

        int maxTitle = maxVisibleTitle();
        int detailStart = 2 + maxTitle + 4;
        int detailWidth = Math.max(8, width - detailStart);

        out.print('\r');
        out.print("\033[K");
        out.print(theme.styled("hr", "─".repeat(Math.max(10, width - 2))));

        for (int i = start; i < end; i++) {
            var item = selector.visibleItem(i - start);
            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            String detail = item.detailText();
            if (i == sel) {
                out.print(theme.styled("statusText", "→ "));
                out.print(theme.styled("heading", item.titleText()));
                out.print(theme.styled("statusText",
                    " ".repeat(Math.max(0, maxTitle - item.titleText().length() + 4))
                    + AnsiWidth.truncateVisual(detail, detailWidth)));
            } else {
                out.print("  ");
                out.print(theme.styled("statusText",
                    item.titleText()
                    + " ".repeat(Math.max(0, maxTitle - item.titleText().length() + 4))
                    + AnsiWidth.truncateVisual(detail, detailWidth)));
            }
        }

        out.print("\r\n");
        out.print('\r');
        out.print("\033[K");
        out.print(theme.styled("statusText", "  (" + (sel + 1) + "/" + total + ")"));

        return NO_CURSOR;
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
