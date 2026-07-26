package org.pijota.tui;

import java.util.List;

final class PromptCompletionPolicy {
    enum Kind {
        NONE, REPLACE, SHOW
    }

    record Decision(Kind kind, String replacement, boolean refreshAfterReplace, List<CompletionItem> items,
                                    String prefix) {
        static Decision none() {
            return new Decision(Kind.NONE, "", false, List.of(), "");
        }

        static Decision replace(String replacement, boolean refreshAfterReplace) {
            return new Decision(Kind.REPLACE, replacement, refreshAfterReplace, List.of(), "");
        }

        static Decision show(List<CompletionItem> items, String prefix) {
            return new Decision(Kind.SHOW, "", false, items != null ? items : List.of(), prefix != null ? prefix : "");
        }
    }

    private final SlashCommandRegistry commands;
    private final PromptFileCompletionProvider fileCompletions;

    PromptCompletionPolicy(SlashCommandRegistry commands, PromptFileCompletionProvider fileCompletions) {
        this.commands = commands;
        this.fileCompletions = fileCompletions != null ? fileCompletions : PromptFileCompletions::complete;
    }

    Decision complete(String text) {
        if (text == null)
            return Decision.none();

        if (text.startsWith("/")) {
            var matches = commands.completeWithDescriptions(text);
            if (!matches.isEmpty()) {
                if (matches.size() == 1)
                    return Decision.replace(commandToken(matches.getFirst().name()), false);

                var prefixMatches = matches.stream().filter(m -> commandToken(m.name()).startsWith(text)).toList();
                if (prefixMatches.size() >= 2) {
                    String common = commonPrefix(prefixMatches);
                    if (common.length() > text.length())
                        return Decision.replace(common, true);
                }
                return Decision.show(matches, "");
            }
        }

        String completionPrefix = fileCompletionPrefix(text);
        var paths = fileCompletions.complete(text);
        if (!paths.isEmpty())
            return Decision.show(paths, completionPrefix);

        return Decision.none();
    }

    List<CompletionItem> refreshCommandCompletions(String text) {
        if (text == null || !text.startsWith("/"))
            return List.of();
        return commands.completeWithDescriptions(text);
    }

    /**
     * Ghost-text suffix for the top command match, rendered dimmed after what the user has typed. Returns only the
     * trailing portion (e.g. {@code "nscend"} for {@code "/tra"} → {@code "/transcend"}), and only when the match
     * genuinely extends the typed text as a prefix — fuzzy (non-prefix) matches get no inline suggestion, since
     * appending their suffix would not read as the completed command.
     */
    String commandSuggestion(String text) {
        if (text == null || !text.startsWith("/"))
            return "";
        var matches = commands.complete(text);
        if (matches.isEmpty())
            return "";
        String first = commandToken(matches.getFirst());
        return first.startsWith(text) && first.length() > text.length() ? first.substring(text.length()) : "";
    }

    private static String fileCompletionPrefix(String text) {
        int lastSpace = text.lastIndexOf(' ');
        return lastSpace >= 0 ? text.substring(0, lastSpace + 1) : "";
    }

    private static String commonPrefix(List<CompletionItem> matches) {
        if (matches.isEmpty())
            return "";
        String prefix = commandToken(matches.getFirst().name());
        for (int i = 1; i < matches.size(); i++) {
            String name = commandToken(matches.get(i).name());
            int len = Math.min(prefix.length(), name.length());
            int common = 0;
            while (common < len && prefix.charAt(common) == name.charAt(common))
                common++;
            prefix = prefix.substring(0, common);
            if (prefix.isEmpty())
                break;
        }
        return prefix;
    }

    private static String commandToken(String name) {
        return name.split("\\s+", 2)[0];
    }
}
