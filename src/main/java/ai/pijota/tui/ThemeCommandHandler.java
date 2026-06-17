package ai.pijota.tui;

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
        var items = host.discoverThemes();
        if (items.isEmpty()) {
            host.appendError("no themes found");
            return;
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
