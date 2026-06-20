package ai.pijota.extensions;

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

    /** Called once at load time — register hooks, tools, commands, etc. via {@code api}. */
    default void init(ExtensionAPI api) throws Exception {}

    /** Called after the core session and UI/runtime have been wired — safe to interact with live state. */
    default void onReady(ExtensionRuntime runtime) throws Exception {}

    /** Called during shutdown, after SessionShutdown subscribers have run. */
    default void onShutdown(ExtensionRuntime runtime) throws Exception {}

    /** Called before each headless sub-agent launch to optionally register tools scoped to that invocation. */
    default void contributeHeadlessTools(HeadlessToolContext ctx) {}

}
