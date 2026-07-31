package org.pijota.tui;

import java.util.Locale;
import java.util.Set;

final class StreamProcessor {
    private static final Set<String> REASONING_PREFIXES = Set.of("the user ", "user wants ", "user asked ", "i'll ",
                                    "i will ", "let me ", "we need ", "need to ");

    private boolean suppressSystemReminder;

    String stripSystemReminders(String line) {
        var out = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            if (suppressSystemReminder) {
                int end = line.indexOf("</system-reminder>", i);
                if (end < 0)
                    return out.toString().stripTrailing();
                i = end + "</system-reminder>".length();
                suppressSystemReminder = false;
                continue;
            }
            int start = line.indexOf("<system-reminder>", i);
            if (start < 0) {
                out.append(line.substring(i));
                break;
            }
            out.append(line, i, start);
            i = start + "<system-reminder>".length();
            suppressSystemReminder = true;
        }
        return out.toString().stripTrailing();
    }

    String stripAndFilter(String line) {
        String cleaned = stripSystemReminders(line);
        if (isReasoningMetaLine(cleaned.strip()))
            return null;
        return cleaned;
    }

    void reset() {
        suppressSystemReminder = false;
    }

    /** Whether mid-{@code <system-reminder>} span (carried across lines), used to skip live preview. */
    boolean suppressing() {
        return suppressSystemReminder;
    }

    static boolean isReasoningMetaLine(String line) {
        if (line.isBlank())
            return false;
        String l = line.toLowerCase(Locale.ROOT);
        for (var prefix : REASONING_PREFIXES)
            if (l.startsWith(prefix))
                return true;
        return l.contains("the user seems");
    }
}
