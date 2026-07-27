package org.pijota.extensions.testkit;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.modes.SlashCommand;

import java.util.List;

public final class DefaultOffPathLoadedExtension implements Extension {
    @Override
    public String name() {
        return "path-default-off";
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public String description() {
        return "Default-off extension loaded from an explicit path.";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerSlashCommand(new SlashCommand("path-default-off", "path default off", List.of(), ignored -> {}));
    }
}
