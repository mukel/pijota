package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;

public final class StatusLineExtension implements Extension {

    @Override
    public String name() {
        return "status-line";
    }

    @Override
    public String description() {
        return "Shows turn progress in status bar";
    }

    @Override
    public void init(ExtensionAPI api) {
        var runtime = api.runtime();

        api.onSessionStart((_ev, _ctx) -> {
            runtime.setStatus("status-demo", "Ready");
        });

        api.onTurnStart((ev, ctx) -> {
            runtime.setStatus("status-demo", "Turn " + ev.turnNumber() + "...");
        });

        api.onTurnEnd((ev, _ctx) -> {
            runtime.setStatus("status-demo", "Turn " + ev.turnNumber() + " complete");
        });

        api.onSessionShutdown((_ev, _ctx) -> {
            runtime.clearStatus("status-demo");
        });
    }
}
