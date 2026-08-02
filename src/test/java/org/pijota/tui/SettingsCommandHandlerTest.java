package org.pijota.tui;

import org.pijota.config.PathDisplay;
import org.pijota.llm.ThinkingLevel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SettingsCommandHandlerTest {
    /**
     * Lowercase, like every other label column in the interface. /context already called the first of these
     * "auto-compact" while this menu called it "Auto-compact": one setting, two spellings, one screen apart.
     */
    @Test
    void buildsSettingsItemsFromSnapshot() {
        var snapshot = new SettingsCommandHandler.SettingsSnapshot(true, false, PathDisplay.COMPACT,
                                        ThinkingLevel.MEDIUM, false, true, "dots");

        var items = SettingsCommandHandler.items(snapshot);

        assertEquals("auto-compact", items.get(0).titleText());
        assertEquals("off", items.get(0).detailText());
        assertEquals("context files", items.get(1).titleText());
        assertEquals("on  next session", items.get(1).detailText());
        assertEquals("path display", items.get(2).titleText());
        assertEquals("compact", items.get(2).detailText());
        assertEquals("thinking", items.get(3).titleText());
        assertEquals("medium", items.get(3).detailText());
        assertEquals("hide thinking", items.get(4).titleText());
        assertEquals("off", items.get(4).detailText());
        assertEquals("quiet startup", items.get(5).titleText());
        assertEquals("on  next start", items.get(5).detailText());
        assertEquals("animation", items.get(6).titleText());
        assertEquals("dots", items.get(6).detailText());
    }

    @Test
    void showSettingsReportsUnavailableSettings() {
        var host = new FakeHost();
        host.available = false;
        var handler = new SettingsCommandHandler(host);

        handler.showSettingsSelector();

        assertTrue(host.errors.contains("settings unavailable"));
        assertTrue(host.shownItems.isEmpty());
    }

    @Test
    void confirmAutoCompactTogglesAndSyncsSessionCompaction() {
        var host = new FakeHost();
        host.compactionEnabled = false;
        host.selected = new SettingsItem("auto_compact", "auto-compact", "off", false);
        var handler = new SettingsCommandHandler(host);

        handler.confirmSettingsSelection();

        assertTrue(host.compactionEnabled);
        assertTrue(host.sessionAutoCompactionEnabled);
        assertFalse(host.refreshedItems.isEmpty());
        assertEquals("auto-compact updated", host.status);
    }

    @Test
    void confirmRestartScopedSettingUsesRestartStatus() {
        var host = new FakeHost();
        host.selected = new SettingsItem("quiet_startup", "quiet startup", "off  next start", false);
        var handler = new SettingsCommandHandler(host);

        handler.confirmSettingsSelection();

        assertTrue(host.quietStartup);
        assertEquals("quiet startup updated for next start/session", host.status);
    }

    @Test
    void confirmThinkingCyclesLevel() {
        var host = new FakeHost();
        host.thinkingLevel = ThinkingLevel.LOW;
        host.selected = new SettingsItem("thinking_level", "thinking", "low", true);
        var handler = new SettingsCommandHandler(host);

        handler.confirmSettingsSelection();

        assertEquals(ThinkingLevel.MEDIUM, host.thinkingLevel);
        assertEquals("thinking updated", host.status);
    }

    private static final class FakeHost implements SettingsCommandHandler.Host {
        boolean available = true;
        boolean contextFilesEnabled = true;
        boolean compactionEnabled = true;
        PathDisplay pathDisplay = PathDisplay.COMPACT;
        ThinkingLevel thinkingLevel = ThinkingLevel.MEDIUM;
        boolean hideThinking;
        boolean quietStartup;
        String workingAnimation = "dots";
        boolean sessionAutoCompactionEnabled;
        SettingsItem selected;
        String status;
        final List<String> errors = new ArrayList<>();
        List<SettingsItem> shownItems = List.of();
        List<SettingsItem> refreshedItems = List.of();

        @Override
        public boolean settingsAvailable() {
            return available;
        }

        @Override
        public SettingsCommandHandler.SettingsSnapshot settingsSnapshot() {
            return new SettingsCommandHandler.SettingsSnapshot(contextFilesEnabled, compactionEnabled, pathDisplay,
                                            thinkingLevel, hideThinking, quietStartup, workingAnimation);
        }

        @Override
        public void showSettings(List<SettingsItem> items) {
            shownItems = items;
        }

        @Override
        public Optional<SettingsItem> selectedSetting() {
            return Optional.ofNullable(selected);
        }

        @Override
        public void refreshSettings(List<SettingsItem> items) {
            refreshedItems = items;
        }

        @Override
        public void clearPrompt() {}

        @Override
        public void redrawPrompt() {}

        @Override
        public void appendError(String message) {
            errors.add(message);
        }

        @Override
        public void toggleCompaction() {
            compactionEnabled = !compactionEnabled;
        }

        @Override
        public void setAutoCompactionEnabled(boolean enabled) {
            sessionAutoCompactionEnabled = enabled;
        }

        @Override
        public void toggleContextFiles() {
            contextFilesEnabled = !contextFilesEnabled;
        }

        @Override
        public void cyclePathDisplay() {
            pathDisplay = pathDisplay.next();
        }

        @Override
        public ThinkingLevel advanceThinkingLevel() {
            thinkingLevel = thinkingLevel.next();
            return thinkingLevel;
        }

        @Override
        public void toggleHideThinking() {
            hideThinking = !hideThinking;
        }

        @Override
        public void toggleQuietStartup() {
            quietStartup = !quietStartup;
        }

        @Override
        public void toggleWorkingAnimation() {
            workingAnimation = workingAnimation.equals("dots") ? "moon" : "dots";
        }

        @Override
        public void setTransientStatus(String status, long millis) {
            assertEquals(2500, millis);
            this.status = status;
        }
    }
}
