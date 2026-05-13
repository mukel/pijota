package ai.pijota.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class DiffAlgorithm {
    private DiffAlgorithm() {}

    public enum Kind { CONTEXT, ADD, DELETE }

    public record Row(Kind kind, int oldLine, int newLine, String text) {}
    public record Hunk(List<Row> rows) {}

    public static List<Row> diff(String oldText, String newText) {
        String[] a = oldText != null && !oldText.isEmpty() ? oldText.split("\\R", -1) : new String[0];
        String[] b = newText != null && !newText.isEmpty() ? newText.split("\\R", -1) : new String[0];
        var items = new ArrayList<KindLine>();
        appendRange(a, 0, a.length, b, 0, b.length, items);
        var out = new ArrayList<Row>();
        int oldLine = 1, newLine = 1;
        for (var item : items) {
            switch (item.kind) {
                case CONTEXT -> out.add(new Row(Kind.CONTEXT, oldLine++, newLine++, item.text));
                case DELETE -> out.add(new Row(Kind.DELETE, oldLine++, 0, item.text));
                case ADD -> out.add(new Row(Kind.ADD, 0, newLine++, item.text));
            }
        }
        return out;
    }

    public static List<Hunk> hunks(List<Row> rows, int context) {
        var out = new ArrayList<Hunk>();
        int i = 0;
        while (i < rows.size()) {
            while (i < rows.size() && rows.get(i).kind() == Kind.CONTEXT) i++;
            if (i >= rows.size()) break;
            int start = Math.max(0, i - context);
            int lastChange = i;
            int nextStart = rows.size();
            for (int j = i + 1; j < rows.size(); j++) {
                if (rows.get(j).kind() == Kind.CONTEXT) continue;
                if (j - lastChange <= context * 2 + 1) lastChange = j;
                else { nextStart = j; break; }
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

    public static long countOld(List<Row> rows) { return rows.stream().filter(r -> r.oldLine() > 0).count(); }
    public static long countNew(List<Row> rows) { return rows.stream().filter(r -> r.newLine() > 0).count(); }

    private record KindLine(Kind kind, String text) {}
    private record Anchor(int oldIndex, int newIndex) {}
    private record Occurrence(int count, int index) {}

    private static void appendRange(String[] a, int aStart, int aEnd,
                                    String[] b, int bStart, int bEnd,
                                    List<KindLine> out) {
        while (aStart < aEnd && bStart < bEnd && a[aStart].equals(b[bStart])) {
            out.add(new KindLine(Kind.CONTEXT, a[aStart]));
            aStart++; bStart++;
        }
        var suffix = new ArrayList<String>();
        while (aStart < aEnd && bStart < bEnd && a[aEnd - 1].equals(b[bEnd - 1])) {
            suffix.add(a[aEnd - 1]);
            aEnd--; bEnd--;
        }
        if (aStart == aEnd || bStart == bEnd) {
            for (int i = aStart; i < aEnd; i++) out.add(new KindLine(Kind.DELETE, a[i]));
            for (int i = bStart; i < bEnd; i++) out.add(new KindLine(Kind.ADD, b[i]));
            for (int i = suffix.size() - 1; i >= 0; i--) out.add(new KindLine(Kind.CONTEXT, suffix.get(i)));
            return;
        }
        var anchors = uniqueAnchors(a, aStart, aEnd, b, bStart, bEnd);
        if (anchors.isEmpty()) {
            for (int i = aStart; i < aEnd; i++) out.add(new KindLine(Kind.DELETE, a[i]));
            for (int i = bStart; i < bEnd; i++) out.add(new KindLine(Kind.ADD, b[i]));
            for (int i = suffix.size() - 1; i >= 0; i--) out.add(new KindLine(Kind.CONTEXT, suffix.get(i)));
            return;
        }
        int lastA = aStart, lastB = bStart;
        for (var anchor : anchors) {
            appendRange(a, lastA, anchor.oldIndex(), b, lastB, anchor.newIndex(), out);
            out.add(new KindLine(Kind.CONTEXT, a[anchor.oldIndex()]));
            lastA = anchor.oldIndex() + 1;
            lastB = anchor.newIndex() + 1;
        }
        appendRange(a, lastA, aEnd, b, lastB, bEnd, out);
        for (int i = suffix.size() - 1; i >= 0; i--) out.add(new KindLine(Kind.CONTEXT, suffix.get(i)));
    }

    private static List<Anchor> uniqueAnchors(String[] a, int aStart, int aEnd,
                                               String[] b, int bStart, int bEnd) {
        var oldInfo = new HashMap<String, Occurrence>();
        var newInfo = new HashMap<String, Occurrence>();
        for (int i = aStart; i < aEnd; i++) {
            int idx = i;
            oldInfo.compute(a[i], (_, v) -> v == null
                ? new Occurrence(1, idx) : new Occurrence(v.count + 1, v.index));
        }
        for (int i = bStart; i < bEnd; i++) {
            int idx = i;
            newInfo.compute(b[i], (_, v) -> v == null
                ? new Occurrence(1, idx) : new Occurrence(v.count + 1, v.index));
        }
        var candidates = new ArrayList<Anchor>();
        for (var entry : oldInfo.entrySet()) {
            var old = entry.getValue();
            var next = newInfo.get(entry.getKey());
            if (old.count == 1 && next != null && next.count == 1) {
                candidates.add(new Anchor(old.index, next.index));
            }
        }
        candidates.sort(java.util.Comparator.comparingInt(Anchor::oldIndex));
        return lisByNewIndex(candidates);
    }

    private static List<Anchor> lisByNewIndex(List<Anchor> anchors) {
        if (anchors.isEmpty()) return List.of();
        int n = anchors.size();
        int[] tails = new int[n];
        int[] prev = new int[n];
        java.util.Arrays.fill(prev, -1);
        int size = 0;
        for (int i = 0; i < n; i++) {
            int value = anchors.get(i).newIndex();
            int lo = 0, hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (anchors.get(tails[mid]).newIndex() < value) lo = mid + 1;
                else hi = mid;
            }
            if (lo > 0) prev[i] = tails[lo - 1];
            tails[lo] = i;
            if (lo == size) size++;
        }
        var out = new ArrayList<Anchor>();
        for (int i = tails[size - 1]; i >= 0; i = prev[i]) out.add(anchors.get(i));
        java.util.Collections.reverse(out);
        return out;
    }
}
