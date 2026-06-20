package ai.pijota.browser;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A browser-automation backend behind {@link BrowserSession}. Since extraction is done entirely by injecting JS and
 * reading values back, the surface is tiny: launch/connect, navigate, evaluate. Implementations are {@link CdpBackend}
 * (Chrome via DevTools) and {@link BiDiBackend} (Firefox/Safari via WebDriver BiDi); calls are serialized by the owning
 * session, so they need no internal locking.
 */
public interface BrowserBackend extends AutoCloseable {

    /**
     * Launches/attaches the browser, connects, and applies the configured viewport and init scripts — idempotent: a
     * no-op when already {@link #isAlive() alive}, and transparently reconnects after a dropped socket.
     */
    void start() throws Exception;

    /**
     * Navigates to {@code url} and blocks until the new document has loaded, using the backend's native protocol event
     * (CDP {@code Page.loadEventFired} / BiDi {@code wait:"complete"}) rather than polling.
     */
    void navigate(String url) throws Exception;

    /**
     * Evaluates a JS expression in the active document and returns its value as plain JSON (by-value, awaiting
     * promises).
     */
    JsonNode evaluate(String js) throws Exception;

    /** Connected and the browser we launched (if any) is still alive. */
    boolean isAlive();

    @Override
    void close();
}
