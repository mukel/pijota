package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * A modal, scrollable, searchable text viewer rendered inside the TUI (no external pager). Owns its scroll position,
 * search state, and viewport, and renders its own window of lines. All navigation/search logic is pure and
 * unit-testable; the host TUI only forwards keys ({@link #handleKey}) and draws {@link #renderLines}.
 *
 * <p>
 * Keys (when active): Up/Down or j/k scroll; PgUp/PgDn page; Home/g top; End/G bottom; {@code /} starts a search (type,
 * Enter to commit, Esc to cancel); n / N jump to next / previous match; Esc or q closes the viewer.
 */
final class LogViewer {

    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");

    private String title = "";
    private List<String> lines = List.of();
    private int top;
    private int viewport = 20;
    private boolean open;

    // search
    private boolean searching;
    private final StringBuilder searchInput = new StringBuilder();
    private String query = "";
    private final List<Integer> matches = new ArrayList<>();
    private int matchPos = -1;

    /** Opens the viewer on the given lines (ANSI is stripped for clean display and search). */
    void show(String title, List<String> rawLines) {
        this.title = title == null ? "" : title;
        var stripped = new ArrayList<String>(rawLines.size());
        for (String l : rawLines)
            stripped.add(ANSI.matcher(l == null ? "" : l).replaceAll(""));
        this.lines = stripped;
        this.top = 0;
        this.open = true;
        this.searching = false;
        this.searchInput.setLength(0);
        this.query = "";
        this.matches.clear();
        this.matchPos = -1;
    }

    boolean active() {
        return open;
    }

    void close() {
        open = false;
        searching = false;
    }

    /** Sets the number of body rows visible; clamps the scroll position to fit. */
    void setViewport(int rows) {
        this.viewport = Math.max(1, rows);
        clampTop();
    }

    int top() {
        return top;
    }

    int total() {
        return lines.size();
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
            top = 0;
        } else if (key == Key.Special.END) {
            top = maxTop();
        } else if (key instanceof Key.Char c) {
            switch (c.value()) {
                case 'q' -> close();
                case 'k' -> scrollBy(-1);
                case 'j' -> scrollBy(1);
                case 'g' -> top = 0;
                case 'G' -> top = maxTop();
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
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(java.util.Locale.ROOT).contains(needle))
                matches.add(i);
        }
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
    }

    private void scrollToLine(int line) {
        top = clamp(line - visibleRows() / 2, 0, maxTop());
    }

    private void clampTop() {
        top = clamp(top, 0, maxTop());
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
        return theme.styled("statusText", "  " + first + "-" + last + "/" + lines.size())
                                        + theme.styled("dim", "  ↑↓ scroll · / search · n/N match · q quit");
    }
}
