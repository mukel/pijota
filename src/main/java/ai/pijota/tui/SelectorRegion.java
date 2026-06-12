package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.PrintWriter;

public final class SelectorRegion<T extends SelectorItem> implements PromptRegion.Custom {
    private static final int MAX_VISIBLE = Selector.MAX_VISIBLE;
    private final Selector<T> selector;

    public SelectorRegion(Selector<T> selector) {
        this.selector = selector;
    }

    @Override
    public int height(int width, PromptSnapshot ctx) {
        if (!selector.active())
            return 0;
        if (selector.total() == 0)
            return 3;
        int h = 1 + selector.visibleCount() + 1;
        int total = selector.total();
        int start = selector.visibleStart();
        if (start > 0)
            h++;
        if (start + MAX_VISIBLE < total)
            h++;
        return h;
    }

    @Override
    public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
        if (!selector.active())
            return NO_CURSOR;

        int sel = selector.selectionIndex();
        int total = selector.total();
        int start = selector.visibleStart();
        int end = start + selector.visibleCount();

        int maxTitle = maxVisibleTitle();
        int detailStart = 2 + maxTitle + 4;
        int detailWidth = Math.max(8, width - detailStart);

        out.print('\r');
        out.print("\033[K");
        String title = selector.title() + (total > 0 ? "  " + total : "");
        out.print(theme.styled("hr", title));

        if (total == 0) {
            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            String message = selector.itemCount() == 0 ? "  nothing to show" : "  no matches";
            out.print(theme.styled("statusText", message));
            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            out.print(theme.styled("dim", "  " + TuiCopy.SELECTOR_HINT));
            return NO_CURSOR;
        }

        if (start > 0) {
            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            out.print(theme.styled("dim", "  ↑ " + start + " more above"));
        }

        for (int i = start; i < end; i++) {
            var item = selector.visibleItem(i - start);
            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            String detail = item.detailText();
            if (i == sel) {
                out.print(theme.styled("prompt", "› "));
                out.print(theme.styled("heading", item.titleText()));
                out.print(theme.styled("statusText", " ".repeat(Math.max(0, maxTitle - item.titleText().length() + 4))
                                                + AnsiWidth.truncateVisual(detail, detailWidth)));
            } else {
                out.print("  ");
                out.print(theme.styled("statusText", item.titleText()
                                                + " ".repeat(Math.max(0, maxTitle - item.titleText().length() + 4))
                                                + AnsiWidth.truncateVisual(detail, detailWidth)));
            }
        }

        if (start + MAX_VISIBLE < total) {
            out.print("\r\n");
            out.print('\r');
            out.print("\033[K");
            out.print(theme.styled("dim", "  ↓ " + (total - start - MAX_VISIBLE) + " more below"));
        }

        out.print("\r\n");
        out.print('\r');
        out.print("\033[K");
        out.print(theme.styled("statusText", "  " + (sel + 1) + "/" + total));
        out.print(theme.styled("dim", "  · " + TuiCopy.SELECTOR_HINT));

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
