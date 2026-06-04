package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class LineAccumulator {
    private final StringBuilder partial = new StringBuilder();

    public List<String> accept(String delta) {
        var lines = new ArrayList<String>();
        if (delta == null || delta.isEmpty()) return lines;
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            if (ch == '\n') {
                lines.add(stripTrailingCr(partial.toString()));
                partial.setLength(0);
            } else {
                partial.append(ch);
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

    private static String stripTrailingCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
