package org.pijota.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DiffAlgorithm {
    private DiffAlgorithm() {}

    public enum Kind {
        CONTEXT, ADD, DELETE
    }

    public record Row(Kind kind, int oldLine, int newLine, String text) {
    }
    public record Hunk(List<Row> rows) {
    }

    /**
     * The same Myers pass over arbitrary units rather than lines, for diffing a line's words against another's.
     *
     * <p>
     * {@code myersDiff} was already generic over arrays; only the line-splitting entry point above was public. The
     * {@code oldLine}/{@code newLine} numbers on each row count units, not lines, and callers that diff words ignore
     * them.
     */
    public static List<Row> diff(String[] oldUnits, String[] newUnits) {
        return rowsFrom(oldUnits == null ? new String[0] : oldUnits, newUnits == null ? new String[0] : newUnits);
    }

    public static List<Row> diff(String oldText, String newText) {
        String[] a = oldText != null && !oldText.isEmpty() ? oldText.split("\\R", -1) : new String[0];
        String[] b = newText != null && !newText.isEmpty() ? newText.split("\\R", -1) : new String[0];
        return diffLines(a, b);
    }

    /**
     * The line diff of two files whose lines are already in hand.
     *
     * <p>
     * Splitting keeps the empty string that follows a final terminator, and that is not a line. Counted, it made a
     * three line file three lines everywhere except here, where the hunk header read {@code @@ -1,4 +1,4 @@} and a
     * blank context row trailed the change -- a patch the model was given and git would not have written.
     *
     * <p>
     * Dropped only when both sides agree about having one, because when they disagree it is the only thing carrying the
     * change, and whether a file ends with a newline is a real property of the file.
     */
    public static List<Row> diffLines(String[] oldLines, String[] newLines) {
        String[] a = oldLines == null ? new String[0] : oldLines;
        String[] b = newLines == null ? new String[0] : newLines;
        if (endsAtLineBoundary(a) == endsAtLineBoundary(b)) {
            a = withoutTrailingBlank(a);
            b = withoutTrailingBlank(b);
        }
        return rowsFrom(a, b);
    }

    /** Whether the text these lines came from ended at a line boundary. An absent or empty file does, vacuously. */
    private static boolean endsAtLineBoundary(String[] lines) {
        return lines.length == 0 || lines[lines.length - 1].isEmpty();
    }

    private static String[] withoutTrailingBlank(String[] lines) {
        return lines.length > 0 && lines[lines.length - 1].isEmpty() ? Arrays.copyOf(lines, lines.length - 1) : lines;
    }

    private static List<Row> rowsFrom(String[] a, String[] b) {
        var edits = myersDiff(a, b);
        var out = new ArrayList<Row>();
        int oldLine = 1, newLine = 1;
        for (var edit : edits) {
            switch (edit.kind) {
                case 0 -> {
                    for (int i = 0; i < edit.len; i++)
                        out.add(new Row(Kind.CONTEXT, oldLine++, newLine++, a[edit.oldStart + i]));
                }
                case -1 -> {
                    for (int i = 0; i < edit.len; i++)
                        out.add(new Row(Kind.DELETE, oldLine++, 0, a[edit.oldStart + i]));
                }
                case 1 -> {
                    for (int i = 0; i < edit.len; i++)
                        out.add(new Row(Kind.ADD, 0, newLine++, b[edit.newStart + i]));
                }
            }
        }
        return out;
    }

    /**
     * Which deleted rows replace which added rows, for the intra-line detail a renderer paints on a pair.
     *
     * <p>
     * A hunk holds runs of deletions followed by runs of additions. Pairing them index-wise up to the shorter run
     * covers what edits actually look like: N lines replaced by N, and, when a line is split or joined, the leading
     * lines still pair and the surplus is left as a plain insertion or deletion. Matching the ragged remainder by
     * similarity would need a metric and a threshold to go with it, and no real diff has yet asked for one.
     *
     * @return for each row index in {@code rows} that has a counterpart, the index of that counterpart
     */
    public static Map<Integer, Integer> pairs(List<Row> rows) {
        var out = new HashMap<Integer, Integer>();
        int i = 0;
        while (i < rows.size()) {
            if (rows.get(i).kind() != Kind.DELETE) {
                i++;
                continue;
            }
            int deleteStart = i;
            while (i < rows.size() && rows.get(i).kind() == Kind.DELETE)
                i++;
            int addStart = i;
            while (i < rows.size() && rows.get(i).kind() == Kind.ADD)
                i++;
            int paired = Math.min(addStart - deleteStart, i - addStart);
            for (int k = 0; k < paired; k++) {
                out.put(deleteStart + k, addStart + k);
                out.put(addStart + k, deleteStart + k);
            }
        }
        return out;
    }

    public static List<Hunk> hunks(List<Row> rows, int context) {
        var out = new ArrayList<Hunk>();
        int i = 0;
        while (i < rows.size()) {
            while (i < rows.size() && rows.get(i).kind() == Kind.CONTEXT)
                i++;
            if (i >= rows.size())
                break;
            int start = Math.max(0, i - context);
            int lastChange = i;
            int nextStart = rows.size();
            for (int j = i + 1; j < rows.size(); j++) {
                if (rows.get(j).kind() == Kind.CONTEXT)
                    continue;
                if (j - lastChange <= context * 2 + 1)
                    lastChange = j;
                else {
                    nextStart = j;
                    break;
                }
            }
            int end = Math.min(rows.size(), lastChange + context + 1);
            out.add(new Hunk(List.copyOf(rows.subList(start, end))));
            i = nextStart == rows.size() ? end : nextStart;
        }
        return out;
    }

    public static int firstOld(List<Row> rows) {
        return rows.stream().filter(r -> r.oldLine() > 0).mapToInt(Row::oldLine).findFirst().orElse(0);
    }

    public static int firstNew(List<Row> rows) {
        return rows.stream().filter(r -> r.newLine() > 0).mapToInt(Row::newLine).findFirst().orElse(0);
    }

    public static long countOld(List<Row> rows) {
        return rows.stream().filter(r -> r.oldLine() > 0).count();
    }

    public static long countNew(List<Row> rows) {
        return rows.stream().filter(r -> r.newLine() > 0).count();
    }

    private record Edit(int kind, int oldStart, int newStart, int len) {
    }

    private static List<Edit> myersDiff(String[] a, String[] b) {
        int n = a.length, m = b.length;
        if (n == 0 && m == 0)
            return List.of();
        if (n == 0)
            return List.of(new Edit(1, 0, 0, m));
        if (m == 0)
            return List.of(new Edit(-1, 0, 0, n));

        int prefix = 0;
        while (prefix < n && prefix < m && a[prefix].equals(b[prefix]))
            prefix++;

        int suffix = 0;
        while (prefix + suffix < n && prefix + suffix < m && a[n - 1 - suffix].equals(b[m - 1 - suffix])) {
            suffix++;
        }

        var edits = new ArrayList<Edit>();
        if (prefix > 0)
            edits.add(new Edit(0, 0, 0, prefix));

        int middleN = n - prefix - suffix;
        int middleM = m - prefix - suffix;
        if (middleN == 0 && middleM == 0) {
            // No middle edit; only the shared prefix/suffix exists.
        } else if (middleN == 0) {
            edits.add(new Edit(1, prefix, prefix, middleM));
        } else if (middleM == 0) {
            edits.add(new Edit(-1, prefix, prefix, middleN));
        } else {
            var middleA = Arrays.copyOfRange(a, prefix, prefix + middleN);
            var middleB = Arrays.copyOfRange(b, prefix, prefix + middleM);
            for (var edit : myersMiddleDiff(middleA, middleB)) {
                edits.add(new Edit(edit.kind, edit.oldStart + prefix, edit.newStart + prefix, edit.len));
            }
        }

        if (suffix > 0)
            edits.add(new Edit(0, n - suffix, m - suffix, suffix));
        return edits;
    }

    private static List<Edit> myersMiddleDiff(String[] a, String[] b) {
        int n = a.length, m = b.length;

        int max = n + m;
        int offset = max;
        int[] v = new int[2 * max + 1];
        var trace = new ArrayList<int[]>();
        int solutionD = -1, solutionK = 0;

        for (int d = 0; d <= max; d++) {
            for (int k = -d; k <= d; k += 2) {
                int idx = k + offset;
                int x;
                if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    x = v[idx + 1];
                } else {
                    x = v[idx - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a[x].equals(b[y])) {
                    x++;
                    y++;
                }
                v[idx] = x;
                if (x >= n && y >= m) {
                    solutionD = d;
                    solutionK = k;
                    trace.add(activeDiagonals(v, offset, d));
                    return backtrack(trace, solutionD, solutionK, a, b, n, m);
                }
            }
            trace.add(activeDiagonals(v, offset, d));
        }
        return List.of();
    }

    private static int[] activeDiagonals(int[] v, int offset, int d) {
        return Arrays.copyOfRange(v, offset - d, offset + d + 1);
    }

    private static int diagonal(int[] compactV, int d, int k) {
        return compactV[k + d];
    }

    private static List<Edit> backtrack(List<int[]> trace, int d, int k, String[] a, String[] b, int n, int m) {
        var edits = new ArrayList<Edit>();
        int x = n, y = m;

        for (int di = d; di > 0; di--) {
            int[] vPrev = trace.get(di - 1);

            boolean right;
            int startX;
            if (k == -di) {
                right = true;
                startX = diagonal(vPrev, di - 1, k + 1);
            } else if (k == di) {
                right = false;
                startX = diagonal(vPrev, di - 1, k - 1);
            } else if (diagonal(vPrev, di - 1, k - 1) < diagonal(vPrev, di - 1, k + 1)) {
                right = true;
                startX = diagonal(vPrev, di - 1, k + 1);
            } else {
                right = false;
                startX = diagonal(vPrev, di - 1, k - 1);
            }

            int oldK = right ? k + 1 : k - 1;
            int startY = startX - oldK;
            int preSnakeX = right ? startX : startX + 1;
            int preSnakeY = right ? startY + 1 : startY;
            int snakeLen = x - preSnakeX;

            if (snakeLen > 0)
                edits.addFirst(new Edit(0, preSnakeX, preSnakeY, snakeLen));

            if (right) {
                edits.addFirst(new Edit(1, startX, startY, 1));
            } else {
                edits.addFirst(new Edit(-1, startX, startY, 1));
            }

            x = startX;
            y = startY;
            k = oldK;
        }

        if (x > 0 && y > 0) {
            int commonLen = Math.min(x, y);
            boolean allMatch = true;
            for (int i = 0; i < commonLen; i++) {
                if (!a[i].equals(b[i])) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch)
                edits.addFirst(new Edit(0, 0, 0, commonLen));
        }

        return edits;
    }
}
