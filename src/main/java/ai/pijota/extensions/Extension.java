package ai.pijota.extensions;

import java.util.Optional;

/**
 * Extension entry point. The extension lifecycle is:
 * <ol>
 * <li>{@link #init(ExtensionAPI)} — register tools, commands, hooks, prompt contributions</li>
 * <li>{@link #onReady(ExtensionRuntime)} — session wired; interact with live state</li>
 * <li>{@link #onShutdown(ExtensionRuntime)} — cleanup before process exit</li>
 * </ol>
 * Additionally, {@link #contributeHeadlessTools(HeadlessToolContext)} is called once per headless sub-agent launch to
 * optionally add tools for that specific invocation.
 */
public interface Extension {

    String name();

    String description();

    /**
     * Whether this extension can run in the current environment. Return a reason to be skipped (logged); empty to load.
     * Checked once before {@link #init}. For hard environment requirements (e.g. a native image, or a missing external
     * binary) — not user opt-in flags.
     */
    default Optional<String> unavailableReason() {
        return Optional.empty();
    }

    /** Called once at load time — register hooks, tools, commands, etc. via {@code api}. */
    default void init(ExtensionAPI api) throws Exception {}

    /** Called after the core session and UI/runtime have been wired — safe to interact with live state. */
    default void onReady(ExtensionRuntime runtime) throws Exception {}

    /** Called once during session shutdown — last chance to persist state or clean up. */
    default void onShutdown(ExtensionRuntime runtime) throws Exception {}

    /** Called before each headless sub-agent launch to optionally register tools scoped to that invocation. */
    default void contributeHeadlessTools(HeadlessToolContext ctx) {}

}
