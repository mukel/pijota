package ai.pijota.extensions;

/**
 * Global exception handler registered by extensions. When an unhandled exception reaches the top-level error path, all
 * registered handlers are called in registration order. The handler receives the throwable and the current
 * {@link ExtensionContext} for UI interaction or logging.
 */
public interface ExceptionHandler {
    void onException(Throwable t, ExtensionContext ctx);
}
