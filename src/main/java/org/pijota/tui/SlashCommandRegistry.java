package org.pijota.tui;

import org.pijota.modes.SlashCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static java.util.Arrays.stream;
import java.util.Optional;
import static java.util.stream.Collectors.toSet;

final class SlashCommandRegistry {
    private static final List<CompletionItem> BUILTIN_COMMANDS = List.of(
                                    new CompletionItem("/help", "Show commands and key bindings"),
                                    new CompletionItem("/settings", "Open settings menu"),
                                    new CompletionItem("/model", "Select or search models"),
                                    new CompletionItem("/theme", "List or switch themes"),
                                    new CompletionItem("/compact", "Manually compact the conversation"),
                                    new CompletionItem("/branch-summary", "Summarize current branch into context"),
                                    new CompletionItem("/export [md|html] [path]",
                                                                    "Export session to Markdown or HTML"),
                                    new CompletionItem("/import <path>", "Import and resume a JSONL session"),
                                    new CompletionItem("/save [path]", "Save session to a file"),
                                    new CompletionItem("/load <id|path>", "Load and resume a session"),
                                    new CompletionItem("/delete <id|path>", "Delete a saved session"),
                                    new CompletionItem("/sessions", "List saved sessions"),
                                    new CompletionItem("/name <name>", "Set session display name"),
                                    new CompletionItem("/context", "Show token usage breakdown"),
                                    new CompletionItem("/usage", "Show cumulative tokens and cost"),
                                    new CompletionItem("/dequeue", "Restore queued follow-up to editor"),
                                    new CompletionItem("/undo", "Edit previous message"),
                                    new CompletionItem("/copy", "Copy last assistant response"),
                                    new CompletionItem("/new", "Start a new session"),
                                    new CompletionItem("/hotkeys", "Show keyboard shortcuts"),
                                    new CompletionItem("/login", "Log in to a provider via OAuth"),
                                    new CompletionItem("/logout", "Remove stored credentials for a provider"),
                                    new CompletionItem("/refresh", "Refresh available models"),
                                    new CompletionItem("/reload", "Reload runtime resources where possible"),
                                    new CompletionItem("/tree", "Show conversation tree"),
                                    new CompletionItem("/fork [message]", "Fork a new session from a previous message"),
                                    new CompletionItem("/remote-control",
                                                                    "Control this session from your phone/browser"),
                                    new CompletionItem("/skills [name]", "List available skills"),
                                    new CompletionItem("/quit", "Exit pijota"));

    private final List<CompletionItem> commands;

    public SlashCommandRegistry() {
        this(List.of());
    }

    public SlashCommandRegistry(List<SlashCommand> extensionCommands) {
        var list = new ArrayList<CompletionItem>(BUILTIN_COMMANDS);
        var builtinNames = BUILTIN_COMMANDS.stream().map(CompletionItem::name).collect(toSet());
        for (var ext : extensionCommands) {
            // A built-in with the same name (e.g. the terminal-native /model) takes precedence here; skip the
            // generic duplicate so completion lists it once.
            if (!builtinNames.contains("/" + ext.name())) {
                String name = "/" + ext.name();
                if (ext.argumentHint() != null && !ext.argumentHint().isBlank())
                    name += " " + ext.argumentHint();
                list.add(new CompletionItem(name, ext.description()));
            }
        }
        this.commands = List.copyOf(list);
    }

    public String helpText() {
        return String.join("\n", helpLines()).stripTrailing();
    }

    public List<String> helpLines() {
        var out = new ArrayList<String>();
        out.add("pijota commands");
        appendGroup(out, "chat", "/help", "/model", "/settings", "/compact", "/branch-summary", "/context", "/usage",
                                        "/dequeue", "/skills");
        appendGroup(out, "session", "/sessions", "/save", "/load", "/export", "/import", "/name", "/new",
                                        "/delete");
        appendGroup(out, "workspace", "/tree", "/fork", "/undo", "/copy", "/theme");
        appendGroup(out, "remote", "/remote-control");
        appendGroup(out, "account", "/login", "/logout", "/refresh", "/reload", "/hotkeys", "/quit");

        var builtins = BUILTIN_COMMANDS.stream().map(c -> commandToken(c.name())).collect(toSet());
        var extensions = commands.stream().filter(c -> !builtins.contains(commandToken(c.name()))).toList();
        if (!extensions.isEmpty())
            appendGroup(out, "extensions", extensions.stream().map(CompletionItem::name).toArray(String[]::new));

        // Keyboard shortcuts are appended by HelpOverlay (the single canonical table) — don't duplicate them here.
        return out;
    }

    private void appendGroup(List<String> out, String title, String... names) {
        out.add("");
        out.add(title);
        int maxLen = stream(names).map(this::command).filter(Optional::isPresent).map(Optional::get)
                                        .map(CompletionItem::name).mapToInt(String::length).max().orElse(0);
        for (String name : names) {
            command(name).ifPresent(cmd -> out.add(String.format("  %-" + (maxLen + 2) + "s %s", cmd.name(),
                                            cmd.description())));
        }
    }

    private Optional<CompletionItem> command(String name) {
        return commands.stream().filter(c -> commandToken(c.name()).equals(name)).findFirst();
    }

    public List<String> complete(String text) {
        if (text == null || !text.startsWith("/"))
            return List.of();
        String prefix = text.split("\\s+", 2)[0].toLowerCase();
        return commands.stream().map(CompletionItem::name)
                                        .filter(name -> fuzzyMatch(prefix, commandToken(name).toLowerCase()))
                                        .sorted(Comparator.comparingInt(name -> matchScore(prefix,
                                                                        commandToken(name).toLowerCase())))
                                        .toList();
    }

    /**
     * Every command, unfiltered. For the command palette, which lists all commands rather than completing a typed "/"
     * prefix — {@link #completeWithDescriptions} returns nothing for a non-slash (e.g. empty) query by design.
     */
    public List<CompletionItem> all() {
        return commands; // already an immutable List.copyOf
    }

    public List<CompletionItem> completeWithDescriptions(String text) {
        if (text == null || !text.startsWith("/"))
            return List.of();
        String prefix = text.split("\\s+", 2)[0].toLowerCase();
        return commands.stream().filter(cmd -> fuzzyMatch(prefix, commandToken(cmd.name()).toLowerCase()))
                                        .sorted(Comparator.comparingInt(cmd -> matchScore(prefix,
                                                                        commandToken(cmd.name()).toLowerCase())))
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

    private static String commandToken(String name) {
        return name.split("\\s+", 2)[0];
    }
}
