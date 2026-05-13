package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class LineAccumulator {
    private final StringBuilder partial = new StringBuilder();

    public List<String> accept(String delta) {
        return accept(delta, 0);
    }

    public List<String> accept(String delta, int softLimit) {
        var lines = new ArrayList<String>();
        if (delta == null || delta.isEmpty()) return lines;
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            if (ch == '\n') {
                lines.add(stripTrailingCr(partial.toString()));
                partial.setLength(0);
            } else {
                partial.append(ch);
                drainSoftLine(lines, softLimit);
            }
        }
        return lines;
    }

    public Optional<String> flush() {
        if (partial.isEmpty()) return Optional.empty();
        String line = stripTrailingCr(partial.toString());
        partial.setLength(0);
        return Optional.of(line);
    }

    public void clear() {
        partial.setLength(0);
    }

    private void drainSoftLine(List<String> lines, int softLimit) {
        if (softLimit <= 0 || partial.length() < softLimit) return;
        int cut = -1;
        int max = Math.min(partial.length(), softLimit);
        for (int i = max - 1; i > softLimit / 2; i--) {
            if (Character.isWhitespace(partial.charAt(i))) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            cut = softLimit;
        }
        String line = partial.substring(0, cut).stripTrailing();
        if (!line.isEmpty()) lines.add(stripTrailingCr(line));
        while (cut < partial.length() && Character.isWhitespace(partial.charAt(cut))) cut++;
        partial.delete(0, cut);
    }

    private static String stripTrailingCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
