package ai.pijota.extensions.examples;

import ai.pijota.extensions.ExtensionEvent;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;

/**
 * Shows turn progress in the status bar via a keyed status entry. Demonstrates the keyed-status-line API; not
 * registered by default.
 */
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

        api.on(ExtensionEvent.SessionStart.class, (_ev, _ctx) -> {
            runtime.setStatus("status-demo", "Ready");
        });

        api.on(ExtensionEvent.TurnStart.class, (ev, ctx) -> {
            runtime.setStatus("status-demo", "Turn " + ev.turnNumber() + "...");
        });

        api.on(ExtensionEvent.TurnEnd.class, (ev, _ctx) -> {
            runtime.setStatus("status-demo", "Turn " + ev.turnNumber() + " complete");
        });

        api.on(ExtensionEvent.SessionShutdown.class, (_ev, _ctx) -> {
            runtime.clearStatus("status-demo");
        });
    }
}
