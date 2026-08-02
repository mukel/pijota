package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A modal, scrollable, searchable text viewer rendered inside the TUI (no external pager). Owns its scroll position,
 * search state, and viewport, and renders its own window of lines. All navigation/search logic is pure and
 * unit-testable; the host TUI only forwards keys ({@link #handleKey}) and draws {@link #renderLines}.
 *
 * <p>
 * Keys (when active): Up/Down or j/k scroll; PgUp/PgDn or Space/b page; Home/g top; End/G bottom; {@code /} starts a
 * search (type, Enter to commit, Esc to cancel); n / N jump to next / previous match; Esc or q closes the viewer.
 */
final class LogViewer {

    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");

    private static final long REFRESH_INTERVAL_MS = 400;

    private String title = "";
    private List<String> lines = List.of();
    private int top;
    private int viewport = 20;
    private boolean open;

    // Live tailing: a non-null source is re-read periodically; follow keeps the view pinned to the tail.
    private Supplier<List<String>> source;
    private boolean follow;
    private long lastRefreshAt;
    private int lastSourceSize = -1; // line count at the last refresh; skips re-stripping an unchanged log

    // search
    private boolean searching;
    private final StringBuilder searchInput = new StringBuilder();
    private String query = "";
    private final List<Integer> matches = new ArrayList<>();
    private int matchPos = -1;

    /** Opens the viewer on a static snapshot (ANSI is stripped for clean display and search). Starts at the top. */
    void show(String title, List<String> rawLines) {
        open(title, rawLines, null);
    }

    /**
     * Opens the viewer on a live source re-read periodically (e.g. a still-running sub-agent's log). Starts pinned to
     * the tail in follow mode, like {@code tail -f}; scrolling up detaches, scrolling/jumping to the bottom
     * re-attaches.
     */
    void showLive(String title, Supplier<List<String>> source) {
        open(title, source.get(), source);
    }

    private void open(String title, List<String> rawLines, Supplier<List<String>> source) {
        this.title = title == null ? "" : title;
        this.lines = strip(rawLines);
        this.source = source;
        this.follow = source != null;
        this.lastRefreshAt = System.currentTimeMillis();
        this.lastSourceSize = rawLines.size();
        this.open = true;
        this.searching = false;
        this.searchInput.setLength(0);
        this.query = "";
        this.matches.clear();
        this.matchPos = -1;
        pin();
    }

    private List<String> strip(List<String> rawLines) {
        var stripped = new ArrayList<String>(rawLines.size());
        for (String l : rawLines)
            stripped.add(ANSI.matcher(l == null ? "" : l).replaceAll(""));
        return stripped;
    }

    /**
     * Re-reads a live source if the refresh interval has elapsed, keeping the tail pinned in follow mode. Returns true
     * if it refreshed (so the host should repaint); false for a static view or before the interval elapses, so an open
     * static viewer (help, a finished log) isn't needlessly repainted on every animation tick.
     */
    boolean refreshIfDue() {
        if (!open || source == null)
            return false;
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < REFRESH_INTERVAL_MS)
            return false;
        lastRefreshAt = now;
        return refresh();
    }

    /**
     * Re-reads the live source now (unthrottled), keeping the tail pinned in follow mode. Returns true if the content
     * changed (so the host should repaint). Skips the re-strip when the line count is unchanged: an append-only log
     * that hasn't grown (e.g. a finished log left open) costs nothing. No-op for static views.
     */
    boolean refresh() {
        if (!open || source == null)
            return false;
        List<String> raw = source.get();
        if (raw.size() == lastSourceSize)
            return false;
        lastSourceSize = raw.size();
        this.lines = strip(raw);
        if (!query.isEmpty())
            recomputeMatches();
        pin();
        return true;
    }

    /** In follow mode keep the viewport at the tail; otherwise just keep the scroll position in range. */
    private void pin() {
        top = follow ? maxTop() : clamp(top, 0, maxTop());
    }

    boolean active() {
        return open;
    }

    void close() {
        open = false;
        searching = false;
    }

    /** Sets the number of body rows visible; keeps the scroll in range (and pinned to the tail in follow mode). */
    void setViewport(int rows) {
        this.viewport = Math.max(1, rows);
        pin();
    }

    int top() {
        return top;
    }

    /** Number of terminal rows the rendered viewer occupies (header + body + footer). */
    int lineCount() {
        return 1 + (lines.isEmpty() ? 1 : visibleRows()) + 1;
    }

    boolean searching() {
        return searching;
    }

    int matchCount() {
        return matches.size();
    }

    /** Handles a key while open; returns true if consumed (the viewer is modal, so it consumes all keys). */
    boolean handleKey(Key key) {
        if (!open)
            return false;
        if (searching)
            return handleSearchKey(key);

        if (key == Key.Special.ESC) {
            close();
            return true;
        }
        if (key == Key.Special.UP) {
            scrollBy(-1);
        } else if (key == Key.Special.DOWN) {
            scrollBy(1);
        } else if (key == Key.Special.PG_UP) {
            scrollBy(-viewport);
        } else if (key == Key.Special.PG_DOWN) {
            scrollBy(viewport);
        } else if (key == Key.Special.HOME) {
            jumpTop();
        } else if (key == Key.Special.END) {
            jumpBottom();
        } else if (key instanceof Key.Char c) {
            switch (c.value()) {
                case 'q' -> close();
                case 'k' -> scrollBy(-1);
                case 'j' -> scrollBy(1);
                case ' ' -> scrollBy(viewport); // less-style: Space pages down
                case 'b' -> scrollBy(-viewport); // less-style: b pages back
                case 'g' -> jumpTop();
                case 'G' -> jumpBottom();
                case '/' -> {
                    searching = true;
                    searchInput.setLength(0);
                }
                case 'n' -> jumpMatch(1);
                case 'N' -> jumpMatch(-1);
                default -> {
                    // swallow other chars (modal)
                }
            }
        }
        return true;
    }

    private boolean handleSearchKey(Key key) {
        if (key == Key.Special.ESC) {
            searching = false;
            return true;
        }
        if (key == Key.Special.ENTER) {
            searching = false;
            commitSearch(searchInput.toString());
            return true;
        }
        if (key == Key.Special.BACKSPACE) {
            if (searchInput.length() > 0)
                searchInput.setLength(searchInput.length() - 1);
            return true;
        }
        if (key instanceof Key.Char c) {
            searchInput.append(c.value());
            return true;
        }
        return true;
    }

    private void commitSearch(String q) {
        query = q == null ? "" : q.trim();
        matches.clear();
        matchPos = -1;
        if (query.isEmpty())
            return;
        recomputeMatches();
        if (!matches.isEmpty()) {
            // jump to the first match at or after the current top, else the first overall
            int target = 0;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i) >= top) {
                    target = i;
                    break;
                }
            }
            matchPos = target;
            scrollToLine(matches.get(matchPos));
        }
    }

    /** Recollects match line indices for the current query (e.g. after a live refresh), clamping the cursor. */
    private void recomputeMatches() {
        matches.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle))
                matches.add(i);
        }
        matchPos = matches.isEmpty() ? -1 : clamp(matchPos < 0 ? 0 : matchPos, 0, matches.size() - 1);
    }

    private void jumpTop() {
        top = 0;
        follow = false;
    }

    private void jumpBottom() {
        top = maxTop();
        follow = source != null;
    }

    private void jumpMatch(int delta) {
        if (matches.isEmpty())
            return;
        matchPos = Math.floorMod(matchPos + delta, matches.size());
        scrollToLine(matches.get(matchPos));
    }

    private int currentMatchLine() {
        return matchPos >= 0 && matchPos < matches.size() ? matches.get(matchPos) : -1;
    }

    private void scrollBy(int delta) {
        top = clamp(top + delta, 0, maxTop());
        follow = source != null && top >= maxTop(); // follow only while parked at the very bottom
    }

    private void scrollToLine(int line) {
        top = clamp(line - visibleRows() / 2, 0, maxTop());
    }

    private int visibleRows() {
        return Math.min(viewport, Math.max(1, lines.size()));
    }

    private int maxTop() {
        return Math.max(0, lines.size() - viewport);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The full rendered window: header, body rows, and footer. */
    List<String> renderLines(AnsiTheme theme, int width) {
        var out = new ArrayList<String>();
        int bodyWidth = Math.max(8, width);
        out.add(theme.styled("hr", header()));
        int rows = lines.isEmpty() ? 0 : visibleRows();
        int current = currentMatchLine();
        // Body lines are rendered flush-left (no prefix/indent) so copy-and-paste yields the raw log text; the
        // current match and other matches are distinguished by colour only (ANSI styling is not copied).
        for (int i = top; i < top + rows && i < lines.size(); i++) {
            String text = AnsiWidth.truncateVisual(lines.get(i), bodyWidth);
            if (i == current) {
                out.add(theme.styled("heading", text));
            } else if (matches.contains(i)) {
                out.add(theme.styled("toolLabel", text));
            } else {
                out.add(theme.styled("statusText", text));
            }
        }
        if (lines.isEmpty())
            out.add(theme.styled("dim", "(empty log)"));
        out.add(footer(theme));
        return out;
    }

    private String header() {
        var sb = new StringBuilder(title.isEmpty() ? "log" : title);
        sb.append("  ").append(lines.size()).append(" lines");
        if (!query.isEmpty()) {
            sb.append("  /").append(query);
            sb.append(matches.isEmpty() ? "  (no matches)" : "  " + (matchPos + 1) + "/" + matches.size());
        }
        return sb.toString();
    }

    private String footer(AnsiTheme theme) {
        if (searching)
            return theme.styled("prompt", "/" + searchInput);
        int last = lines.isEmpty() ? 0 : Math.min(lines.size(), top + visibleRows());
        int first = lines.isEmpty() ? 0 : top + 1;
        String position = first + "-" + last + "/" + lines.size() + (follow ? " · following" : "");
        return TuiCopy.footer(theme, position, "↑↓ scroll", "Space/b page", "/ search", "n/N match", "q quit");
    }
}
