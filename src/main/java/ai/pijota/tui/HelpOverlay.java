package ai.pijota.tui;

import ai.pijota.extensions.ExtensionKeybinding;
import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The {@code /help} (and {@code /hotkeys}) overlay: builds the help + keyboard-shortcuts content and holds it while
 * shown. The host feeds {@link #lines()} into the prompt snapshot (via {@code withHelpOverlay}) and PromptRegion draws
 * it. A pure builder plus a little state, so the content is unit-testable without a terminal.
 */
final class HelpOverlay {

    private static final String[][] SHORTCUTS = {{"Enter", "submit prompt"}, {"Ctrl+J", "newline"},
            {"Tab", "autocomplete"}, {"Shift+Tab", "toggle thinking"}, {"Up/Down", "history / navigate"},
            {"Esc", "interrupt (double-force)"}, {"Ctrl+C", "clear / double to exit"}, {"Ctrl+K", "delete to end"},
            {"Ctrl+U", "clear line"}, {"Ctrl+W", "delete word"}, {"Ctrl+A/E", "start/end of line"},
            {"Ctrl+L", "select model"}, {"Ctrl+P", "command palette"}, {"Ctrl+Shift+P", "cycle model forward"},
            {"Ctrl+R", "reverse history search"}, {"Ctrl+G", "open in editor"}, {"/help", "show help"}};

    private volatile List<String> lines = List.of();

    boolean active() {
        return !lines.isEmpty();
    }

    List<String> lines() {
        return lines;
    }

    void clear() {
        lines = List.of();
    }

    /** Builds and shows the overlay from the slash-command help, the fixed shortcuts, and extension keybindings. */
    void show(AnsiTheme theme, List<String> commandHelp, Collection<ExtensionKeybinding> keybindings) {
        var out = new ArrayList<String>();
        for (String raw : commandHelp) {
            if (raw.startsWith("  ") && raw.contains("  ")) {
                int split = raw.indexOf("  ", 2);
                if (split < 0) {
                    out.add(theme.styled("statusText", raw));
                    continue;
                }
                out.add(theme.styled("toolLabel", raw.substring(0, split))
                                                + theme.styled("statusText", raw.substring(split)));
            } else {
                out.add(theme.styled("heading", raw));
            }
        }
        out.add("");
        out.add(theme.styled("heading", "keyboard shortcuts"));
        int keyWidth = 0;
        for (var k : SHORTCUTS)
            keyWidth = Math.max(keyWidth, AnsiWidth.visibleWidth(k[0]));
        keyWidth += 2;
        for (var k : SHORTCUTS)
            out.add(theme.styled("toolLabel", padRight(k[0], keyWidth)) + theme.styled("statusText", k[1]));
        for (var binding : keybindings)
            out.add(theme.styled("toolLabel", padRight(binding.key().toString(), keyWidth))
                                            + theme.styled("statusText", binding.description()));
        lines = out;
    }

    private static String padRight(String s, int width) {
        return s + " ".repeat(Math.max(0, width - AnsiWidth.visibleWidth(s)));
    }
}
