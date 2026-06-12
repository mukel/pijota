package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PromptHistory {
    private final List<String> entries = new ArrayList<>();
    private int index = -1;
    private String preNavigationText = "";

    boolean isEmpty() {
        return entries.isEmpty();
    }

    boolean isNavigating() {
        return index >= 0;
    }

    boolean isReverseSearchItemCount(int itemCount) {
        return !entries.isEmpty() && itemCount == entries.size();
    }

    void add(String text) {
        if (text == null || text.isBlank())
            return;
        entries.remove(text);
        entries.add(text);
        index = -1;
        preNavigationText = "";
    }

    Optional<String> previous(String currentText) {
        if (entries.isEmpty())
            return Optional.empty();
        if (index < 0) {
            preNavigationText = currentText != null ? currentText : "";
            index = entries.size() - 1;
        } else if (index > 0) {
            index--;
        } else {
            return Optional.empty();
        }
        return Optional.of(entries.get(index));
    }

    Optional<String> next() {
        if (index < 0)
            return Optional.empty();
        index++;
        if (index >= entries.size()) {
            index = -1;
            return Optional.of(preNavigationText);
        }
        return Optional.of(entries.get(index));
    }

    List<CompletionItem> reverseSearchItems() {
        return entries.reversed().stream().map(entry -> new CompletionItem(entry, "history")).toList();
    }

    String suggestionFor(String text) {
        String prefix = text != null ? text : "";
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            if (entry.regionMatches(true, 0, prefix, 0, prefix.length()) && entry.length() > prefix.length())
                return entry;
        }
        return "";
    }
}
