package ai.pijota.tui;

import ai.pijota.modes.SlashCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SlashCommandRegistry {
    private final List<CompletionItem> commands;

    public SlashCommandRegistry() {
        this(List.of());
    }

    public SlashCommandRegistry(List<SlashCommand> extensionCommands) {
        var list = new ArrayList<CompletionItem>();
        for (var cmd : BuiltInSlashCommand.values()) {
            list.add(new CompletionItem(cmd.name, cmd.description));
        }
        for (var ext : extensionCommands) {
            list.add(new CompletionItem("/" + ext.name(), ext.description()));
        }
        this.commands = List.copyOf(list);
    }

    public String helpText() {
        var out = new StringBuilder("commands");
        int maxLen = commands.stream().mapToInt(c -> c.name().length()).max().orElse(0);
        for (var cmd : commands) {
            out.append('\n').append(String.format("  %-" + (maxLen + 2) + "s %s", cmd.name(), cmd.description()));
        }
        out.append("\n\nkeys: Ctrl+J newline | Ctrl+X Ctrl+E editor | Esc interrupt | Ctrl+C exit");
        return out.toString();
    }

    public List<String> complete(String text) {
        if (text == null || !text.startsWith("/")) return List.of();
        String prefix = text.split("\\s+", 2)[0].toLowerCase();
        return commands.stream()
            .map(CompletionItem::name)
            .filter(name -> fuzzyMatch(prefix, name.toLowerCase()))
            .sorted(Comparator.comparingInt(name -> matchScore(prefix, name.toLowerCase())))
            .toList();
    }

    public List<CompletionItem> completeWithDescriptions(String text) {
        if (text == null || !text.startsWith("/")) return List.of();
        String prefix = text.split("\\s+", 2)[0].toLowerCase();
        return commands.stream()
            .filter(cmd -> fuzzyMatch(prefix, cmd.name().toLowerCase()))
            .sorted(Comparator.comparingInt(cmd -> matchScore(prefix, cmd.name().toLowerCase())))
            .toList();
    }

    static boolean fuzzyMatch(String query, String target) {
        if (query.isEmpty()) return true;
        int qi = 0;
        for (int ti = 0; ti < target.length() && qi < query.length(); ti++) {
            if (target.charAt(ti) == query.charAt(qi)) qi++;
        }
        if (qi == query.length()) return true;
        return target.contains(query);
    }

    static int matchScore(String query, String target) {
        if (target.equals(query)) return 0;
        if (target.startsWith(query)) return 1;
        int qi = 0, last = -1, gap = 0;
        for (int ti = 0; ti < target.length() && qi < query.length(); ti++) {
            if (target.charAt(ti) == query.charAt(qi)) {
                if (last >= 0) gap += ti - last - 1;
                last = ti;
                qi++;
            }
        }
        return 10 + gap;
    }
}
