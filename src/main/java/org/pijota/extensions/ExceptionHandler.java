package org.pijota.extensions;

/**
 * Global exception handler registered by extensions. When an unhandled exception reaches the top-level error path, all
 * registered handlers are called in registration order. Handlers that need UI reach it via {@code api.runtime().ui()}.
 */
public interface ExceptionHandler {
    void onException(Throwable t);
}
