package ai.pijota.extensions.testkit;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.modes.SlashCommand;

import java.util.List;

public final class PathLoadedExtension implements Extension {
    @Override
    public String name() {
        return "path-loaded";
    }

    @Override
    public String description() {
        return "Loaded from an explicit extension path.";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerSlashCommand(new SlashCommand("path-loaded", "path loaded", List.of(), _ -> {}));
    }
}
