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
            rows.add(TuiCopy.footer(theme, "", TuiCopy.SELECTOR_HINT));
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
                                            + fitDetail(item.detailText(), detailWidth);
            if (i == sel) {
                rows.add(theme.styled("prompt", "› ") + highlight(theme, title, "heading", match)
                                                + theme.styled("statusText", tail));
            } else {
                rows.add("  " + highlight(theme, title, "statusText", match) + theme.styled("statusText", tail));
            }
        }

        if (start + MAX_VISIBLE < total)
            rows.add(theme.styled("dim", "  ↓ " + (total - start - MAX_VISIBLE) + " more below"));

        rows.add(TuiCopy.footer(theme, (sel + 1) + "/" + total, TuiCopy.SELECTOR_HINT));
        return Body.of(rows);
    }

    /**
     * A row's detail, narrowed to {@code room} by dropping whole facts rather than slicing through one.
     *
     * <p>
     * Every selector writes its detail as {@code fact · fact · fact}, so the separator is where a cut belongs. A plain
     * visual truncation cut wherever the column ran out, and at 100 columns the model list read
     * {@code context 976K · input text · price $0.14/$} on every row -- a severed number, which does not look narrow,
     * it looks broken. Dropping the last fact instead leaves a shorter row that is still entirely true.
     *
     * <p>
     * The first fact is the one exception: if even it does not fit there is nothing to drop, so it is cut and marked
     * with an ellipsis, which at least says so.
     */
    static String fitDetail(String detail, int room) {
        if (detail == null || room <= 0)
            return "";
        if (AnsiWidth.visibleWidth(detail) <= room)
            return detail;
        String[] facts = detail.split(" · ", -1);
        var kept = new StringBuilder(facts[0]);
        for (int i = 1; i < facts.length; i++) {
            String next = kept + " · " + facts[i];
            if (AnsiWidth.visibleWidth(next) > room)
                break;
            kept.setLength(0);
            kept.append(next);
        }
        return AnsiWidth.visibleWidth(kept.toString()) <= room
                                        ? kept.toString()
                                        : AnsiWidth.truncateVisual(facts[0], Math.max(1, room - 1)) + "…";
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
