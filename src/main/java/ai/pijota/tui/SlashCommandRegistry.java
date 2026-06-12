package ai.pijota.tui;

import ai.pijota.modes.SlashCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SlashCommandRegistry {
    private static final List<CompletionItem> BUILTIN_COMMANDS = List.of(
                                    new CompletionItem("/help", "Show commands and key bindings"),
                                    new CompletionItem("/settings", "Open settings menu"),
                                    new CompletionItem("/model", "Select or search models"),
                                    new CompletionItem("/theme", "List or switch themes"),
                                    new CompletionItem("/compact", "Manually compact the conversation"),
                                    new CompletionItem("/export", "Export session to Markdown"),
                                    new CompletionItem("/save", "Save session to a file"),
                                    new CompletionItem("/load", "Load and resume a session"),
                                    new CompletionItem("/delete", "Delete a saved session"),
                                    new CompletionItem("/sessions", "List saved sessions"),
                                    new CompletionItem("/name", "Set session display name"),
                                    new CompletionItem("/context", "Show token usage breakdown"),
                                    new CompletionItem("/undo", "Edit previous message"),
                                    new CompletionItem("/copy", "Copy last assistant response"),
                                    new CompletionItem("/session", "Show session info"),
                                    new CompletionItem("/whoami", "Show current model info"),
                                    new CompletionItem("/new", "Start a new session"),
                                    new CompletionItem("/hotkeys", "Show keyboard shortcuts"),
                                    new CompletionItem("/login", "Log in to a provider via OAuth"),
                                    new CompletionItem("/logout", "Remove stored credentials for a provider"),
                                    new CompletionItem("/refresh", "Refresh available models"),
                                    new CompletionItem("/tree", "Show conversation tree"),
                                    new CompletionItem("/fork", "Fork a new session from a previous message"),
                                    new CompletionItem("/clone", "Clone current branch to a new session"),
                                    new CompletionItem("/transcend", "Rebuild and restart pijota, or set a self-modification goal"),
                                    new CompletionItem("/skills", "List available skills"),
                                    new CompletionItem("/quit", "Exit pijota"));

    private final List<CompletionItem> commands;

    public SlashCommandRegistry() {
        this(List.of());
    }

    public SlashCommandRegistry(List<SlashCommand> extensionCommands) {
        var list = new ArrayList<CompletionItem>(BUILTIN_COMMANDS);
        for (var ext : extensionCommands) {
            list.add(new CompletionItem("/" + ext.name(), ext.description()));
        }
        this.commands = List.copyOf(list);
    }

    public String helpText() {
        var out = new StringBuilder("pijota commands\n");
        appendGroup(out, "chat", "/help", "/model", "/settings", "/compact", "/context", "/whoami", "/skills");
        appendGroup(out, "session", "/session", "/sessions", "/save", "/load", "/export", "/name", "/new", "/delete");
        appendGroup(out, "workspace", "/tree", "/fork", "/clone", "/undo", "/copy", "/theme", "/transcend");
        appendGroup(out, "account", "/login", "/logout", "/refresh", "/hotkeys", "/quit");

        var builtins = BUILTIN_COMMANDS.stream().map(CompletionItem::name).collect(java.util.stream.Collectors.toSet());
        var extensions = commands.stream().filter(c -> !builtins.contains(c.name())).toList();
        if (!extensions.isEmpty())
            appendGroup(out, "extensions", extensions.stream().map(CompletionItem::name).toArray(String[]::new));

        out.append("\nkeys\n");
        out.append("  Enter submit · Ctrl+J newline · Ctrl+G editor · Esc interrupt · Ctrl+C clear/exit\n");
        out.append("  Ctrl+L model picker · Ctrl+P cycle model · Shift+Tab thinking\n");
        return out.toString().stripTrailing();
    }

    private void appendGroup(StringBuilder out, String title, String... names) {
        out.append('\n').append(title).append('\n');
        int maxLen = java.util.Arrays.stream(names).mapToInt(String::length).max().orElse(0);
        for (String name : names) {
            command(name).ifPresent(cmd -> out.append(String.format("  %-" + (maxLen + 2) + "s %s%n", cmd.name(),
                                            cmd.description())));
        }
    }

    private java.util.Optional<CompletionItem> command(String name) {
        return commands.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public List<String> complete(String text) {
        if (text == null || !text.startsWith("/"))
            return List.of();
        String prefix = text.split("\\s+", 2)[0].toLowerCase();
        return commands.stream().map(CompletionItem::name).filter(name -> fuzzyMatch(prefix, name.toLowerCase()))
                                        .sorted(Comparator.comparingInt(name -> matchScore(prefix, name.toLowerCase())))
                                        .toList();
    }

    public List<CompletionItem> completeWithDescriptions(String text) {
        if (text == null || !text.startsWith("/"))
            return List.of();
        String prefix = text.split("\\s+", 2)[0].toLowerCase();
        return commands.stream().filter(cmd -> fuzzyMatch(prefix, cmd.name().toLowerCase())).sorted(
                                        Comparator.comparingInt(cmd -> matchScore(prefix, cmd.name().toLowerCase())))
                                        .toList();
    }

    static boolean fuzzyMatch(String query, String target) {
        if (query.isEmpty())
            return true;
        int qi = 0;
        for (int ti = 0; ti < target.length() && qi < query.length(); ti++) {
            if (target.charAt(ti) == query.charAt(qi))
                qi++;
        }
        if (qi == query.length())
            return true;
        return target.contains(query);
    }

    static int matchScore(String query, String target) {
        if (target.equals(query))
            return 0;
        if (target.startsWith(query))
            return 1;
        int qi = 0, last = -1, gap = 0;
        for (int ti = 0; ti < target.length() && qi < query.length(); ti++) {
            if (target.charAt(ti) == query.charAt(qi)) {
                if (last >= 0)
                    gap += ti - last - 1;
                last = ti;
                qi++;
            }
        }
        return 10 + gap;
    }
}
