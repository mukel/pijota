package ai.pijota.extensions;

import ai.pijota.messages.Message;

import java.util.List;

/**
 * Convenience wrapper around {@link UiContext} and {@link ExtensionRuntime}, passed to every event handler. All methods
 * null-guard against missing UI/runtime so handlers work in every frontend without boilerplate.
 */
public final class ExtensionContext {
    private final UiContext ui;
    private final ExtensionRuntime runtime;

    public ExtensionContext(UiContext ui, ExtensionRuntime runtime) {
        this.ui = ui;
        this.runtime = runtime;
    }

    public UiContext ui() {
        return ui;
    }

    public ExtensionRuntime runtime() {
        return runtime;
    }

    public boolean hasUI() {
        return ui != null;
    }

    public List<Message> messages() {
        return runtime != null ? runtime.messages() : List.of();
    }

    public void compact(String instructions) {
        if (runtime != null)
            runtime.compact(instructions);
    }

    public void shutdown() {
        if (runtime != null && !runtime.isClosed())
            runtime.abort();
    }
}
