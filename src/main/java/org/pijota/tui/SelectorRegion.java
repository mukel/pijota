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
            String title = item.titleText();
            int[] match = SlashCommandRegistry.matchPositions(selector.query(), title);
            // The "this is the active one" marker gets its own fixed column, so a marked row's detail still starts
            // where every other row's does. Each selector used to bake its own marker into its text (three different
            // glyphs across models, themes and the tree), and the model one pushed that row's provider out of line.
            String marker = item.selected() ? theme.styled("prompt", "● ") : "  ";
            String tail = " ".repeat(Math.max(0, maxTitle - title.length() + 2)) + marker
                                            + AnsiWidth.truncateVisual(item.detailText(), detailWidth);
            if (i == sel) {
                rows.add(theme.styled("prompt", "› ") + highlight(theme, title, "heading", match)
                                                + theme.styled("statusText", tail));
            } else {
                rows.add("  " + highlight(theme, title, "statusText", match) + theme.styled("statusText", tail));
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

    /**
     * {@code text} in {@code baseSlot}, with the matched characters at {@code match} bolded (kept in the base colour).
     */
    static String highlight(AnsiTheme theme, String text, String baseSlot, int[] match) {
        if (match.length == 0)
            return theme.styled(baseSlot, text);
        var hit = new boolean[text.length()];
        for (int p : match)
            if (p >= 0 && p < hit.length)
                hit[p] = true;
        var sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) { // coalesce consecutive same-class runs so each emits one style + reset
            boolean h = hit[i];
            int j = i;
            while (j < text.length() && hit[j] == h)
                j++;
            String run = text.substring(i, j);
            // Matched chars keep the row's own style and get the marker PREFIXED (styled() ends with a full reset, so
            // the marker clears with the run). The marker is an attribute, not a colour, so it reads the same on a
            // muted row and on the selected one; the previous "pure bold" dropped the selected row's accent colour,
            // which made matches read DULLER than the text around them.
            String styled = theme.styled(baseSlot, run);
            sb.append(h ? theme.style("selectorMatch") + styled : styled);
            i = j;
        }
        return sb.toString();
    }
}
