package ai.pijota.browser;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A browser-automation backend behind {@link BrowserSession}. Because all page extraction is done by injecting
 * JavaScript and reading values back, the protocol surface a backend must provide is tiny: launch/connect, issue a
 * navigation, and evaluate an expression. Everything else — the settle/scroll heuristics, Readability, the extraction
 * tiers and markdown conversion — is browser-agnostic and lives in {@link BrowserSession}.
 *
 * <p>
 * Implementations: {@link CdpBackend} (Chrome/Edge via the DevTools Protocol) and, in a later step, a WebDriver BiDi
 * backend for Firefox/Safari. Calls are serialized by the owning {@link BrowserSession}, so implementations need no
 * internal locking.
 */
public interface BrowserBackend extends AutoCloseable {

    /**
     * Launches/attaches the browser, connects, and applies the configured viewport and init scripts — idempotent: a
     * no-op when already {@link #isAlive() alive}, and transparently reconnects after a dropped socket.
     */
    void start() throws Exception;

    /**
     * Issues a navigation to {@code url} (returns once it has started; the caller awaits load via {@link #evaluate}).
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
