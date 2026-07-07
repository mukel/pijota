package ai.pijota.extensions.testkit;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.modes.SlashCommand;

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
        api.registerSlashCommand(new SlashCommand("path-default-off", "path default off", List.of(), _ -> {}));
    }
}
