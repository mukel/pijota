package org.pijota.tui;

import java.util.List;
import java.util.Optional;

final class ThemeCommandHandler {
    interface Host extends TuiHost {
        List<ThemeItem> discoverThemes();

        void showThemes(List<ThemeItem> items);

        void selectCurrentTheme();

        Optional<ThemeItem> selectedTheme();

        void switchToTheme(String name);

        void switchToThemePath(String path);

        void deactivateThemeSelector();
    }

    private final Host host;

    ThemeCommandHandler(Host host) {
        this.host = host;
    }

    void showThemeSelector() {
        showThemeSelector("");
    }

    /**
     * {@code /theme} opens the picker; {@code /theme <name>} switches straight to an exactly-named theme, like
     * {@code /model <id>} does. Anything else (a typo, a partial name) falls through to the picker, which the user can
     * then search, rather than failing with an error.
     */
    void showThemeSelector(String arg) {
        var items = host.discoverThemes();
        if (items.isEmpty()) {
            host.appendError("no themes found");
            return;
        }
        if (arg != null && !arg.isBlank()) {
            String wanted = arg.strip();
            var exact = items.stream().filter(t -> t.name().equalsIgnoreCase(wanted)).findFirst();
            if (exact.isPresent()) {
                var theme = exact.get();
                if (theme.path().isBlank())
                    host.switchToTheme(theme.name());
                else
                    host.switchToThemePath(theme.path());
                host.clearPrompt();
                host.redrawPrompt();
                return;
            }
        }
        host.showThemes(items);
        host.selectCurrentTheme();
        host.clearPrompt();
        host.redrawPrompt();
    }

    void confirmThemeSelection() {
        var chosen = host.selectedTheme().orElse(null);
        if (chosen == null)
            return;
        if (chosen.path().isBlank())
            host.switchToTheme(chosen.name());
        else
            host.switchToThemePath(chosen.path());
        host.deactivateThemeSelector();
        host.clearPrompt();
        host.redrawPrompt();
    }
}
