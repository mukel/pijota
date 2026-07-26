package org.pijota.extensions.examples;

import org.pijota.extensions.ExtensionEvent;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;

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

        api.on(ExtensionEvent.SessionStart.class, _ev -> {
            runtime.setStatus("status-demo", "Ready");
        });

        api.on(ExtensionEvent.TurnStart.class, ev -> {
            runtime.setStatus("status-demo", "Turn " + ev.turnNumber() + "...");
        });

        api.on(ExtensionEvent.TurnEnd.class, ev -> {
            runtime.setStatus("status-demo", "Turn " + ev.turnNumber() + " complete");
        });

    }

    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        runtime.clearStatus("status-demo");
    }
}
