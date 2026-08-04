package org.pijota.tui;

import org.pijota.modes.SyntaxHighlighter;
import org.pijota.theme.AnsiTheme;
import org.pijota.util.DiffAlgorithm;
import org.pijota.util.WordDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class DiffRenderer {
    private static final int CONTEXT_LINES = 3;
    private static final int MAX_RENDERED_ROWS = 300;
    private static final int MIN_RULE = 5; // the header always ends in at least a stub of rule
    private static final Pattern ANSI_RESET = Pattern.compile("\033\\[0m");

    private final AnsiTheme theme;

    public DiffRenderer(AnsiTheme theme) {
        this.theme = theme;
    }

    /**
     * Splits into lines, with every character a terminal would obey rather than print rewritten to the caret notation
     * {@code less} and {@code git} show it in.
     *
     * <p>
     * A diff draws file content, and file content is not trusted: an ESC in a line reaches the terminal as a command.
     * {@code ESC[2J} clears the transcript, {@code ESC[H} moves the cursor, and either lets whoever wrote the file
     * paint text that looks like it came from pijota. That is worth more than the display glitch it looks like, since
     * the whole point of showing a diff is that the reader can trust what it says.
     *
     * <p>
     * Escaping here, before the split feeds anything else, is what keeps it simple: the diff, the word offsets, the
     * highlighter and the wrap all see the same string the terminal will, so no stage has to agree about raw versus
     * drawn coordinates. Tab survives, because it is layout rather than a command and the wrap already expands it. C1
     * controls are left alone: a UTF-8 terminal prints those rather than obeying them, as an escaped one confirms.
     */
    private static String[] printableLines(String text) {
        if (text == null || text.isEmpty())
            return new String[0];
        var out = text.split("\\R", -1);
        for (int i = 0; i < out.length; i++)
            out[i] = printable(out[i]);
        return out;
    }

    private static String printable(String line) {
        StringBuilder escaped = null; // the overwhelmingly common case is a line with nothing to escape
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch >= ' ' && ch != '\177' || ch == '\t') {
                if (escaped != null)
                    escaped.append(ch);
                continue;
            }
            if (escaped == null)
                escaped = new StringBuilder(line.length() + 8).append(line, 0, i);
            escaped.append('^').append((char) (ch ^ 0x40)); // 0 -> @, ESC -> [, DEL -> ?
        }
        return escaped == null ? line : escaped.toString();
    }

    /** Whether the text these lines came from ended at a line boundary. An absent or empty file does, vacuously. */
    private static boolean endsAtLineBoundary(String[] lines) {
        return lines.length == 0 || lines[lines.length - 1].isEmpty();
    }

    private static String[] withoutTrailingBlank(String[] lines) {
        return endsAtLineBoundary(lines) && lines.length > 0 ? java.util.Arrays.copyOf(lines, lines.length - 1) : lines;
    }

    public List<String> render(String action, String path, String oldText, String newText, int width) {
        String[] oldLines = printableLines(oldText);
        String[] newLines = printableLines(newText);
        // Splitting keeps the empty string that follows a final terminator, and that is not a line: it put a row one
        // past the end of the file at the foot of every diff, and counted a five line file as six additions where git
        // counts five. Dropped only when both sides agree about having one, because when they disagree it is the only
        // thing that says a file gained or lost its final newline, which is a real change and worth showing.
        if (endsAtLineBoundary(oldLines) == endsAtLineBoundary(newLines)) {
            oldLines = withoutTrailingBlank(oldLines);
            newLines = withoutTrailingBlank(newLines);
        }
        // Diff the printable form, not the raw one, so line numbers, word offsets and what is drawn all agree.
        var rows = DiffAlgorithm.diff(oldLines, newLines);
        int added = 0;
        int deleted = 0;
        for (var row : rows) {
            if (row.kind() == DiffAlgorithm.Kind.ADD)
                added++;
            if (row.kind() == DiffAlgorithm.Kind.DELETE)
                deleted++;
        }
        if (added == 0 && deleted == 0)
            return List.of();

        var highlighter = new SyntaxHighlighter(SyntaxHighlighter.languageForFilename(path), theme);
        var hunks = DiffAlgorithm.hunks(rows, CONTEXT_LINES);
        int maxOld = Math.max(1, oldLines.length);
        int maxNew = Math.max(1, newLines.length);
        int oldWidth = digits(maxOld);
        int newWidth = digits(maxNew);
        int terminalWidth = Math.max(40, width);

        var out = new ArrayList<String>();
        // The block's line of air goes above its header, not between the header and the rows it labels. Every other
        // block in the interface opens that way; the coordinator collapses the blank if something already added one.
        out.add("");
        out.add(header(action, path, added, deleted, terminalWidth));
        int rendered = 0;
        int shownChanged = 0;
        boolean truncated = false;

        for (int h = 0; h < hunks.size(); h++) {
            var hunk = hunks.get(h);
            // A blank line between hunks says a gap without saying it skipped anything, so the numbers either side of
            // it look contiguous until the reader checks them. Naming the count is the whole job of the separator.
            if (h > 0)
                out.add(separator(hunks.get(h - 1).rows(), hunk.rows()));
            // Which deleted row each added row replaced, so a pair can be compared word by word.
            var pairs = DiffAlgorithm.pairs(hunk.rows());
            for (int r = 0; r < hunk.rows().size(); r++) {
                if (rendered >= MAX_RENDERED_ROWS) {
                    truncated = true;
                    break;
                }
                var row = hunk.rows().get(r);
                // Computed here rather than up front: rows past the cap are never drawn, and this runs on the redraw.
                out.addAll(format(row, oldWidth, newWidth, highlighter.highlightLine(row.text()), terminalWidth,
                                                wordSpans(hunk.rows(), pairs, r)));
                rendered++;
                if (row.kind() != DiffAlgorithm.Kind.CONTEXT)
                    shownChanged++;
            }
            if (truncated)
                break;
        }

        if (truncated) {
            // Counted, not assumed: the cap is on rows drawn, and context rows are drawn too, so a diff cut at 300
            // rows has shown fewer than 300 changed ones. Saying what is missing is also the more useful half.
            int missing = added + deleted - shownChanged;
            out.add(theme.styled("diffMeta", "… diff truncated: " + missing
                                            + (missing == 1 ? " more changed row" : " more changed rows")
                                            + " not shown"));
        }
        return out;
    }

    /**
     * The line between two hunks, naming how many unchanged lines it stands for.
     *
     * <p>
     * The count comes from the old-file numbering, which both sides share across a gap: the run between two hunks is
     * unchanged by definition, so it is the same length on either side.
     */
    private String separator(List<DiffAlgorithm.Row> before, List<DiffAlgorithm.Row> after) {
        int last = lastOldLine(before);
        int first = firstOldLine(after);
        int skipped = first - last - 1;
        if (skipped <= 0)
            return ""; // nothing countable to say; the blank line still separates the hunks
        return theme.styled("diffMeta", "  ⋮ " + skipped + (skipped == 1 ? " unchanged line" : " unchanged lines"));
    }

    /** Added rows carry no old-file number, so the ends of a hunk are found by skipping them. */
    private static int lastOldLine(List<DiffAlgorithm.Row> rows) {
        for (int i = rows.size() - 1; i >= 0; i--)
            if (rows.get(i).oldLine() > 0)
                return rows.get(i).oldLine();
        return 0;
    }

    private static int firstOldLine(List<DiffAlgorithm.Row> rows) {
        for (var row : rows)
            if (row.oldLine() > 0)
                return row.oldLine();
        return 0;
    }

    /** The ranges of row {@code index} that differ from the row it replaced, or none when it replaced nothing. */
    private static List<WordDiff.Span> wordSpans(List<DiffAlgorithm.Row> rows, java.util.Map<Integer, Integer> pairs,
                                    int index) {
        Integer other = pairs.get(index);
        if (other == null)
            return List.of();
        var row = rows.get(index);
        var counterpart = rows.get(other);
        boolean isNew = row.kind() == DiffAlgorithm.Kind.ADD;
        var result = isNew
                                        ? WordDiff.spans(counterpart.text(), row.text())
                                        : WordDiff.spans(row.text(), counterpart.text());
        return isNew ? result.newSpans() : result.oldSpans();
    }

    private String header(String action, String path, int added, int deleted, int width) {
        String name = path == null || path.isBlank() ? "diff" : shortenPath(path);
        // Budget the path to what is left after the label, the counts and a stub of rule. An absolute path longer than
        // the terminal used to wrap mid-path, so the header broke across two rows and the second row started with the
        // middle of a directory name. Truncating the middle keeps both ends, and the filename is the end that matters.
        int fixed = action.length() + 1 + 2 + ("+" + added).length() + 1 + ("-" + deleted).length() + 1 + MIN_RULE;
        name = AnsiWidth.truncateMiddle(name, Math.max(12, width - fixed));
        // Label first, then the rule filling the line. The header used to open with "- ", which sat right next to the
        // "-0" deleted count and above a gutter of "-" markers, so the one dash that meant "rule" looked like the
        // three that mean "removed". The rule is drawn with ─, the same glyph as every other rule in the UI.
        String text = theme.styled("diffHeader", action + " " + name) + "  " + theme.styled("diffAdd", "+" + added)
                                        + " " + theme.styled("diffDelete", "-" + deleted) + " ";
        // width - 1: a header that fills the last column is flagged wrapped, and a later resize splits it in the
        // scrollback it already landed in. See PromptRegion.rule.
        int fill = Math.max(MIN_RULE, width - 1 - AnsiWidth.visibleWidth(text));
        return text + theme.styled("diffMeta", "─".repeat(fill));
    }

    /**
     * One diff row, as the physical lines it occupies. A row longer than the terminal is wrapped here rather than left
     * to the terminal, which would restart the overflow at column zero: the code would then sit outside the line-number
     * gutter and under a different row's numbers. Continuations are indented to the gutter width, so wrapped code stays
     * under the code column and the numbers still read straight down.
     */
    private List<String> format(DiffAlgorithm.Row row, int oldWidth, int newWidth, String body, int width) {
        return format(row, oldWidth, newWidth, body, width, List.of());
    }

    /**
     * @param spans
     *            the ranges of this row's text that differ from the line it replaced, in the row's own coordinates;
     *            empty when the pair was too dissimilar for word detail to help, or when the row is not part of a pair
     */
    private List<String> format(DiffAlgorithm.Row row, int oldWidth, int newWidth, String body, int width,
                                    List<WordDiff.Span> spans) {
        String oldNo = row.oldLine() > 0 ? pad(row.oldLine(), oldWidth) : " ".repeat(oldWidth);
        String newNo = row.newLine() > 0 ? pad(row.newLine(), newWidth) : " ".repeat(newWidth);
        String mark = switch (row.kind()) {
            case ADD -> "+";
            case DELETE -> "-";
            case CONTEXT -> " ";
        };
        String slot = switch (row.kind()) {
            case ADD -> "diffAdd";
            case DELETE -> "diffDelete";
            case CONTEXT -> "diffContext";
        };
        String bgSlot = switch (row.kind()) {
            case ADD -> "diffAddLine";
            case DELETE -> "diffDeleteLine";
            case CONTEXT -> null; // context rows carry no highlight, so no background to extend
        };
        String prefix = theme.styled("diffMeta", "  " + oldNo + " " + newNo + " ") + theme.styled(slot, mark) + " ";
        String indent = " ".repeat(AnsiWidth.visibleWidth(prefix));
        // The trailing space carries the row's background to the edge of the change; context rows have none.
        String pad = bgSlot == null ? "" : " ";
        String wordSlot = switch (row.kind()) {
            case ADD -> "diffAddWord";
            case DELETE -> "diffDeleteWord";
            case CONTEXT -> null;
        };
        if (bgSlot == null)
            return AnsiWidth.wrapPrefixed(body, width, prefix, r -> indent + r).stream().map(line -> line + pad)
                                            .toList();

        String rowBg = theme.style(bgSlot);
        String wordBg = spans.isEmpty() || wordSlot == null ? "" : theme.style(wordSlot);
        // Painted before wrapping, where the spans and the text still share coordinates. Wrapping consumes the space
        // it breaks at, so fragments do not reassemble into the body and any offset carried across them drifts by one
        // character per wrap. wrapPreservingAnsi re-opens the SGR state on each continuation, so the backgrounds ride
        // along instead.
        String painted = withBackground(body, 0,
                                        offset -> wordBg.isBlank() || !WordDiff.covers(spans, offset) ? rowBg : wordBg);
        // width - 1 leaves room for the pad, which is appended after wrapping. Without it a row that fills the
        // terminal exactly comes out one column too wide, the terminal wraps it, and a blank line appears in the
        // middle of a wrapped row. The header guards its own rule the same way, for the same reason.
        // The gutter is two styled runs and a bare space, and each styled run closes with a full reset that takes the
        // row background down with it. That left the marker and the space after it as the only cells of a changed row
        // without one: a two column notch bitten out of the highlight, right where the eye goes to read the sign.
        // Painting it re-opens the background after each reset, exactly as the body is painted below.
        var fragments = AnsiWidth.wrapPrefixed(painted, width - 1, withBackground(prefix, 0, offset -> rowBg),
                                        r -> indent + r);
        // rowBg twice: once so the gutter carries it, and again for the pad, because the painted body ends with a
        // reset. Without the second the row background stopped at the last character instead of one column past it,
        // which is what marks where the changed text ends.
        return fragments.stream().map(fragment -> rowBg + fragment + rowBg + pad + theme.reset()).toList();
    }

    private String withBackground(String slot, String line) {
        return withBackground(line, 0, ignored -> theme.style(slot));
    }

    /**
     * Paints backgrounds over an already highlighted line, changing which one applies as the offset moves.
     *
     * <p>
     * Two facts about the highlighter make this a single pass with no state to track, and
     * {@code HighlighterInvariantsTest} pins both: no syntax colour ever sets a background, and every close is spelled
     * {@code ESC[0m}. So the only thing that can clear the background is a full reset, and re-emitting after one
     * restores it without disturbing the foreground that reset belonged to.
     *
     * @param rawStart
     *            the offset, in the logical line's own coordinates, that this fragment begins at. Wrapping splits a row
     *            into fragments after the spans were computed, so each fragment resumes where the last left off.
     * @param backgroundAt
     *            the background in force at a raw offset, blank for none
     */
    private String withBackground(String line, int rawStart, java.util.function.IntFunction<String> backgroundAt) {
        var sb = new StringBuilder();
        String current = "";
        int raw = rawStart;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == 0x1b) {
                int end = line.indexOf('m', i);
                if (end < 0)
                    end = line.length() - 1;
                String esc = line.substring(i, end + 1);
                sb.append(esc);
                // A full reset drops the background with the colour it was closing, so put it back.
                if (ANSI_RESET.matcher(esc).matches() && !current.isBlank())
                    sb.append(current);
                i = end;
                continue;
            }
            String wanted = backgroundAt.apply(raw);
            if (!wanted.equals(current)) {
                // Leaving a background needs a reset; entering one only needs the background.
                if (wanted.isBlank())
                    sb.append(theme.reset());
                sb.append(wanted);
                current = wanted;
            }
            sb.append(ch);
            raw++;
        }
        if (!current.isBlank())
            sb.append(theme.reset());
        return sb.toString();
    }

    private static String shortenPath(String path) {
        String home = System.getProperty("user.home");
        if (home != null && path.startsWith(home))
            path = "~" + path.substring(home.length());
        return path;
    }

    private static int digits(int value) {
        return Integer.toString(Math.max(1, value)).length();
    }

    private static String pad(int value, int width) {
        return String.format(Locale.ROOT, "%" + width + "d", value);
    }
}
