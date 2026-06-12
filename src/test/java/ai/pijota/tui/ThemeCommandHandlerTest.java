package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ThemeCommandHandlerTest {
    @Test
    void showThemeSelectorReportsNoThemes() {
        var host = new FakeHost();
        var handler = new ThemeCommandHandler(host);

        handler.showThemeSelector();

        assertEquals(List.of("no themes found"), host.errors);
        assertTrue(host.shownThemes.isEmpty());
    }

    @Test
    void showThemeSelectorShowsThemesAndSelectsCurrent() {
        var host = new FakeHost();
        host.discoveredThemes = List.of(new ThemeItem("dark", "", true), new ThemeItem("light", "", false));
        var handler = new ThemeCommandHandler(host);

        handler.showThemeSelector();

        assertEquals(2, host.shownThemes.size());
        assertTrue(host.selectedCurrent);
        assertTrue(host.promptCleared);
        assertEquals(1, host.redraws);
    }

    @Test
    void confirmThemeSelectionSwitchesBuiltinThemeAndClosesSelector() {
        var host = new FakeHost();
        host.selectedTheme = new ThemeItem("dark", "", true);
        var handler = new ThemeCommandHandler(host);

        handler.confirmThemeSelection();

        assertEquals("dark", host.switchedTheme);
        assertNull(host.switchedPath);
        assertTrue(host.deactivated);
        assertTrue(host.promptCleared);
        assertEquals(1, host.redraws);
    }

    @Test
    void confirmThemeSelectionSwitchesPathTheme() {
        var host = new FakeHost();
        host.selectedTheme = new ThemeItem("custom", "/tmp/custom.json", false);
        var handler = new ThemeCommandHandler(host);

        handler.confirmThemeSelection();

        assertEquals("/tmp/custom.json", host.switchedPath);
        assertNull(host.switchedTheme);
    }

    @Test
    void confirmThemeSelectionIgnoresMissingSelection() {
        var host = new FakeHost();
        var handler = new ThemeCommandHandler(host);

        handler.confirmThemeSelection();

        assertNull(host.switchedTheme);
        assertNull(host.switchedPath);
        assertFalse(host.deactivated);
    }

    private static final class FakeHost implements ThemeCommandHandler.Host {
        List<ThemeItem> discoveredThemes = List.of();
        List<ThemeItem> shownThemes = List.of();
        ThemeItem selectedTheme;
        String switchedTheme;
        String switchedPath;
        boolean selectedCurrent;
        boolean promptCleared;
        boolean deactivated;
        int redraws;
        final List<String> errors = new ArrayList<>();

        @Override
        public List<ThemeItem> discoverThemes() {
            return discoveredThemes;
        }

        @Override
        public void showThemes(List<ThemeItem> items) {
            shownThemes = items;
        }

        @Override
        public void selectCurrentTheme() {
            selectedCurrent = true;
        }

        @Override
        public Optional<ThemeItem> selectedTheme() {
            return Optional.ofNullable(selectedTheme);
        }

        @Override
        public void clearPrompt() {
            promptCleared = true;
        }

        @Override
        public void redrawPrompt() {
            redraws++;
        }

        @Override
        public void appendError(String message) {
            errors.add(message);
        }

        @Override
        public void switchToTheme(String name) {
            switchedTheme = name;
        }

        @Override
        public void switchToThemePath(String path) {
            switchedPath = path;
        }

        @Override
        public void deactivateThemeSelector() {
            deactivated = true;
        }
    }
}
