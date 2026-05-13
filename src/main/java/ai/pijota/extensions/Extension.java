package ai.pijota.extensions;

/**
 * Extension entry point. Implementations register tools, commands, prompt
 * contributions, and event handlers from {@link #init(ExtensionAPI)}.
 */
public interface Extension {

    String name();
    String description();

    /**
     * Called when the extension is first loaded.
     * Use the API object to register hooks, tools, commands, etc.
     */
    default void init(ExtensionAPI api) throws Exception {}

    /** Called after the core session and UI/runtime have been wired. */
    default void onReady(ExtensionRuntime runtime) throws Exception {}

    /** Called during shutdown after session_shutdown subscribers have run. */
    default void onShutdown(ExtensionRuntime runtime) throws Exception {}

    default void contributeHeadlessTools(HeadlessToolContext ctx) {}

}
