package org.pijota.tui;

import org.pijota.agent.SessionDescriptor;
import org.pijota.theme.AnsiTheme;
import org.pijota.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The lines pijota opens with: the wordmark, the model it is about to use, and the last few sessions you could pick up
 * again.
 *
 * <p>
 * A pure function wearing a method before this: everything it needs arrives as an argument and the only side effect,
 * committing the lines, belongs to the caller. Out of {@link AppendOnlyTui} for that reason -- and because the column
 * arithmetic underneath is the part that has actually gone wrong (a meta built at full length while only the title was
 * cut to fit), which is worth being able to test at a width without a terminal.
 */
final class StartupBanner {

    /** Columns a session title keeps whatever else wants room; below this a title says nothing at all. */
    private static final int MIN_TITLE = 8;

    private StartupBanner() {}

    /**
     * The wordmark, split the way the web UI splits it: {@code pi} plain, {@code jota} in the accent. The shared root
     * stays quiet and the part that is pijota's carries the colour, which reads as a name rather than as a version
     * string and tells the lineage without a sentence about it.
     */
    static String wordmark(AnsiTheme theme) {
        return theme.styled("bannerRoot", "pi") + theme.styled("bannerName", "jota");
    }

    static List<String> lines(AnsiTheme theme, String version, String provider, String model, boolean quiet, int width,
                                    Supplier<List<Path>> resumableSessions) {
        if (quiet) {
            return List.of(wordmark(theme) + theme.styled("bannerMeta", " " + version));
        }
        var lines = new ArrayList<String>();
        // Three levels, loudest last-but-one: the name carries the accent, the model is the fact worth reading
        // every launch, and the version is trivia. They shared one slot, so the eye had nothing to land on.
        lines.add(wordmark(theme) + theme.styled("bannerMeta", " " + version) + TuiCopy.GROUP
                                        + theme.styled("commandMeta", PromptPresenter.modelLabel(provider, model)));
        lines.add(theme.styled("dim", TuiCopy.START_HINT));

        // recent sessions
        try {
            var items = SessionItem.fromPaths(resumableSessions.get(), 3);
            if (!items.isEmpty()) {
                lines.add("");
                lines.add(theme.styled("dim", "recent sessions"));
                // The row is GROUP + title + GROUP + meta, so the two separators cost 4 columns and the title is
                // promised MIN_TITLE of what remains. The meta is built to that budget rather than measured after
                // the fact: it used to be built at full length and only the title was cut to fit, so once the meta
                // alone was wider than the terminal the floor below guaranteed the overflow it was meant to prevent.
                var metas = items.stream().map(it -> bannerSessionMeta(it, width - 4 - MIN_TITLE)).toList();
                // Metadata in one column so ages and counts can be compared straight down the list; trailing each
                // title, it landed somewhere different on every row. The column sits just past the longest title
                // rather than against the right margin, which would leave a canyon of blank the eye has to cross.
                int metaWidth = metas.stream().mapToInt(AnsiWidth::visibleWidth).max().orElse(0);
                int titleRoom = Math.max(MIN_TITLE, width - metaWidth - 4); // 2 leading + one GROUP gap
                // Columns, not characters: a CJK title cut to N characters is 2N columns wide, and the row wrapped.
                var titles = items.stream().map(it -> AnsiWidth.ellipsize(it.description(), titleRoom)).toList();
                int titleColumn = titles.stream().mapToInt(AnsiWidth::visibleWidth).max().orElse(0);
                // The title carries the emphasis and the age gives way, which was the wrong way round: statusText is
                // dimmed and commandMeta is not, so the eye landed on "1h ago" rather than on which session it was.
                for (int i = 0; i < titles.size(); i++)
                    lines.add(theme.styled("commandMeta",
                                                    TuiCopy.GROUP + AppendOnlyTui.padRight(titles.get(i), titleColumn))
                                                    + TuiCopy.GROUP + theme.styled("statusText", metas.get(i)));
            }
        } catch (Exception ignored) {}
        return lines;
    }

    /**
     * The banner's one-glance metadata for a session. Built from the item's own fields rather than by cutting up the
     * selector's detail string, which also carried an absolute timestamp and the session id: at three rows the question
     * is "which one was I just in", and a relative age answers it without a second time format competing.
     */
    static String bannerSessionMeta(SessionItem item, int budget) {
        var parts = new ArrayList<String>();
        String age = SessionDescriptor.relativeAge(item.mtime());
        if (age != null && !age.isBlank())
            parts.add(age);
        if (item.messageCount() > 0) {
            parts.add(Util.plural(item.messageCount(), "msg"));
            if (item.estTokens() > 0)
                parts.add("~" + SessionDescriptor.formatTokens(item.estTokens()));
        }
        // Built most-telling-first, so a narrow terminal gives up the tail: at three rows the question is "which
        // session was I just in", which the age answers and the token estimate does not. Saying less on one row
        // beats saying everything across two, which is what wrapping actually costs.
        while (!parts.isEmpty() && AnsiWidth.visibleWidth(String.join(" · ", parts)) > budget)
            parts.remove(parts.size() - 1);
        return String.join(" · ", parts);
    }
}
