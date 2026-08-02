package org.pijota.tui;

import org.pijota.config.PathDisplay;
import org.pijota.llm.ThinkingLevel;

import java.util.List;
import java.util.Optional;

final class SettingsCommandHandler {
    interface Host extends TuiHost {
        boolean settingsAvailable();

        SettingsSnapshot settingsSnapshot();

        void showSettings(List<SettingsItem> items);

        Optional<SettingsItem> selectedSetting();

        void refreshSettings(List<SettingsItem> items);

        void toggleCompaction();

        void setAutoCompactionEnabled(boolean enabled);

        void toggleContextFiles();

        void cyclePathDisplay();

        ThinkingLevel advanceThinkingLevel();

        void toggleHideThinking();

        void toggleQuietStartup();

        void toggleWorkingAnimation();

        void setTransientStatus(String status, long millis);
    }

    record SettingsSnapshot(boolean contextFilesEnabled, boolean compactionEnabled, PathDisplay pathDisplay,
                                    ThinkingLevel thinkingLevel, boolean hideThinking, boolean quietStartup,
                                    String workingAnimation) {
    }

    private final Host host;

    SettingsCommandHandler(Host host) {
        this.host = host;
    }

    void showSettingsSelector() {
        if (!host.settingsAvailable()) {
            host.appendError("settings unavailable");
            return;
        }
        host.showSettings(items(host.settingsSnapshot()));
        host.clearPrompt();
        host.redrawPrompt();
    }

    void confirmSettingsSelection() {
        var chosen = host.selectedSetting().orElse(null);
        if (chosen == null)
            return;
        switch (chosen.id()) {
            case "auto_compact" -> {
                host.toggleCompaction();
                host.setAutoCompactionEnabled(host.settingsSnapshot().compactionEnabled());
            }
            case "context_files" -> host.toggleContextFiles();
            case "thinking_level" -> host.advanceThinkingLevel();
            case "hide_thinking" -> host.toggleHideThinking();
            case "quiet_startup" -> host.toggleQuietStartup();
            case "path_display" -> host.cyclePathDisplay();
            case "animation" -> host.toggleWorkingAnimation();
            default -> {}
        }
        host.refreshSettings(items(host.settingsSnapshot()));
        host.setTransientStatus(statusMessage(chosen), 2500);
    }

    /**
     * Lowercase, like every other label column in the interface. {@code /context} already called the first of these
     * "auto-compact" while this menu called it "Auto-compact" -- one setting, two spellings, one screen apart.
     */
    static List<SettingsItem> items(SettingsSnapshot snapshot) {
        return List.of(new SettingsItem("auto_compact", "auto-compact", snapshot.compactionEnabled() ? "on" : "off",
                                        snapshot.compactionEnabled()),
                                        new SettingsItem("context_files", "context files",
                                                                        snapshot.contextFilesEnabled()
                                                                                                        ? "on  next session"
                                                                                                        : "off  next session",
                                                                        snapshot.contextFilesEnabled()),
                                        new SettingsItem("path_display", "path display",
                                                                        snapshot.pathDisplay().config(), true),
                                        new SettingsItem("thinking_level", "thinking", snapshot.thinkingLevel().label(),
                                                                        true),
                                        new SettingsItem("hide_thinking", "hide thinking",
                                                                        snapshot.hideThinking() ? "on" : "off",
                                                                        snapshot.hideThinking()),
                                        new SettingsItem("quiet_startup", "quiet startup", snapshot.quietStartup()
                                                                        ? "on  next start"
                                                                        : "off  next start", snapshot.quietStartup()),
                                        new SettingsItem("animation", "animation", snapshot.workingAnimation(), true));
    }

    static String statusMessage(SettingsItem chosen) {
        return chosen.id().equals("context_files") || chosen.id().equals("quiet_startup")
                                        ? chosen.titleText() + " updated for next start/session"
                                        : chosen.titleText() + " updated";
    }
}
