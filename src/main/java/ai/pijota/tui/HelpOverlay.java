package ai.pijota.tui;

import ai.pijota.extensions.ExtensionKeybinding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the {@code /help} (and {@code /hotkeys}) content: the slash-command groups, the fixed keyboard shortcuts, and
 * any extension keybindings. The host shows the result in the scrollable {@link LogViewer}, so this is a pure builder
 * with no state — the viewer owns scrolling, search, and fit-to-terminal.
 */
final class HelpOverlay {

    private static final String[][] SHORTCUTS = {{"Enter", "submit prompt"}, {"Ctrl+J", "newline"},
            {"Tab", "autocomplete"}, {"Shift+Tab", "toggle thinking"}, {"Up/Down", "history / navigate"},
            {"Esc", "interrupt (double-force)"}, {"Ctrl+C", "clear / double to exit"}, {"Ctrl+K", "delete to end"},
            {"Ctrl+U", "clear line"}, {"Ctrl+W", "delete word"}, {"Ctrl+A/E", "start/end of line"}, {"Ctrl+_", "undo"},
            {"Alt+_", "redo"}, {"Ctrl+Y", "yank (paste last kill)"}, {"Alt+Y", "yank-pop"}, {"Ctrl+L", "select model"},
            {"Ctrl+P", "command palette"}, {"Ctrl+Shift+P", "cycle model forward"},
            {"Ctrl+R", "reverse history search"}, {"Ctrl+G", "open in editor"},
            {"Ctrl+O", "expand/collapse status bar"}, {"@", "fzf file completion (@ref)"},
            {"Ctrl+T", "fzf path completion (raw)"}, {"/help", "show help"}};

    private HelpOverlay() {}

    /** {@code /help} layout: slash commands first, keyboard shortcuts after. */
    static List<String> content(List<String> commandHelp, Collection<ExtensionKeybinding> keybindings) {
        return content(commandHelp, keybindings, false);
    }

    /**
     * @param shortcutsFirst
     *            when true (the {@code /hotkeys} view), list keyboard shortcuts before the slash commands; otherwise
     *            commands come first (the {@code /help} view).
     */
    static List<String> content(List<String> commandHelp, Collection<ExtensionKeybinding> keybindings,
                                    boolean shortcutsFirst) {
        var shortcuts = shortcutsBlock(keybindings);
        var out = new ArrayList<String>();
        if (shortcutsFirst) {
            out.addAll(shortcuts);
            out.add("");
            out.addAll(commandHelp);
        } else {
            out.addAll(commandHelp);
            out.add("");
            out.addAll(shortcuts);
        }
        return out;
    }

    private static List<String> shortcutsBlock(Collection<ExtensionKeybinding> keybindings) {
        var out = new ArrayList<String>();
        out.add("keyboard shortcuts");
        int keyWidth = 0;
        for (var k : SHORTCUTS)
            keyWidth = Math.max(keyWidth, k[0].length());
        for (var binding : keybindings)
            keyWidth = Math.max(keyWidth, binding.key().toString().length());
        keyWidth += 2;
        for (var k : SHORTCUTS)
            out.add("  " + padRight(k[0], keyWidth) + k[1]);
        for (var binding : keybindings)
            out.add("  " + padRight(binding.key().toString(), keyWidth) + binding.description());
        return out;
    }

    private static String padRight(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }
}
