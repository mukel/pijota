package org.pijota.browser;

import org.pijota.tools.WebfetchTool;
import org.pijota.util.Env;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reads JS-rendered pages and search results the way a person's browser sees them (not a blocked headless fetch) and
 * returns markdown. Owns the browser-agnostic part (lazy-load settling, Mozilla Readability, the visible-DOM extraction
 * tiers, markdown conversion) over a pluggable {@link BrowserBackend}. Calls are serialized so they can't race the
 * protocol.
 */
public final class BrowserSession {

    private static final int SCROLL_STEPS = 12; // max progressive scrolls to trigger lazy-loaded content
    private static final int VIEW_W = 1365, VIEW_H = 900; // emulated viewport (matches ds4) so layout is deterministic
    private static final int MAX_HTML = 1_500_000; // cap the rendered DOM pulled back (fallback path only)

    /**
     * Wall-clock waits for the settle heuristics that have <i>no</i> protocol event: "the visible text stopped growing"
     * (SPA/lazy content) and the consent-dismissal reflow. The load wait itself is event-driven in the backend
     * ({@code Page.loadEventFired} / BiDi {@code wait:"complete"}), so it isn't here. Production values match the tuned
     * ds4 behavior; tests set {@code -Dpijota.browser.fastSettle} to collapse them (an instantly-ready fake has nothing
     * to settle), keeping the suite fast without changing production behavior.
     */
    record Timings(long scrollStepMs, long stabilityPollMs, long consentMs, long stabilityCapMs, int stableReads) {
        static final Timings DEFAULT = new Timings(450, 250, 300, 6_000, 3);
        static final Timings FAST = new Timings(0, 0, 0, 300, 1);

        static Timings fromEnv() {
            return Boolean.parseBoolean(Env.get("pijota.browser.fastSettle", "PIJOTA_BROWSER_FAST_SETTLE"))
                                            ? FAST
                                            : DEFAULT;
        }
    }

    private final BrowserBackend backend;
    private final Timings timings;
    private volatile boolean cancelled; // tripped by close() to interrupt the in-flight navigate/settle loops

    // Close the spawned browser after it sits idle this long (re-launched lazily on the next use); 0 disables it.
    private final long idleNanos = idleTimeoutNanos();
    private volatile long lastUseNanos = System.nanoTime();
    private final ScheduledExecutorService idleReaper = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "pijota-browser-idle");
        t.setDaemon(true);
        return t;
    });

    public BrowserSession(BrowserBackend backend) {
        this(backend, Timings.fromEnv());
    }

    BrowserSession(BrowserBackend backend, Timings timings) {
        this.backend = backend;
        this.timings = timings;
        if (idleNanos > 0) {
            long checkSec = Math.max(5, idleNanos / 1_000_000_000L / 4); // poll ~4x per idle window
            idleReaper.scheduleWithFixedDelay(this::reapIfIdle, checkSec, checkSec, TimeUnit.SECONDS);
        }
    }

    private static long idleTimeoutNanos() {
        String v = Env.get("pijota.browser.idleSeconds", "PIJOTA_BROWSER_IDLE_SECONDS");
        long seconds = 300; // default: close the browser after 5 min idle
        if (v != null && !v.isBlank())
            try {
                seconds = Long.parseLong(v.trim());
            } catch (NumberFormatException ignored) {}
        return seconds <= 0 ? 0 : seconds * 1_000_000_000L;
    }

    /** Closes the browser when it's been idle past the timeout; the next visit/search/extractPdf re-launches it. */
    private void reapIfIdle() {
        try {
            synchronized (this) {
                if (shouldReap(backend.isAlive(), lastUseNanos, System.nanoTime(), idleNanos))
                    backend.close();
            }
        } catch (Exception ignored) {
            // a reaper failure must not cancel the fixed-delay schedule
        }
    }

    static boolean shouldReap(boolean alive, long lastUseNanos, long nowNanos, long idleNanos) {
        return alive && idleNanos > 0 && nowNanos - lastUseNanos > idleNanos;
    }

    public BrowserSession(int port, Path profileDir, String chromeOverride, Consumer<String> log,
                                    Callable<Boolean> confirmLaunch) {
        this(new CdpBackend(port, profileDir, chromeOverride, log, confirmLaunch, VIEW_W, VIEW_H,
                                        List.of(READABILITY_JS)), Timings.fromEnv());
    }

    /**
     * Builds a session for the named browser: {@code chrome} (default, via the DevTools Protocol) or {@code firefox} /
     * {@code safari} (via WebDriver BiDi). {@code binaryOverride} is the Chrome binary for chrome, the driver path for
     * the BiDi browsers (or null → resolved from {@code PIJOTA_GECKODRIVER}/{@code PIJOTA_SAFARIDRIVER}/{@code PATH}).
     */
    public static BrowserSession create(String browser, int port, Path profileDir, String binaryOverride,
                                    Consumer<String> log, Callable<Boolean> confirmLaunch) {
        String b = browser == null || browser.isBlank() ? "chrome" : browser.strip().toLowerCase();
        List<String> initScripts = List.of(READABILITY_JS);
        return switch (b) {
            case "chrome", "chromium", "edge" ->
                new BrowserSession(port, profileDir, binaryOverride, log, confirmLaunch);
            case "firefox" -> new BrowserSession(BiDiBackends.firefox(port, binaryOverride, log, confirmLaunch, VIEW_W,
                                            VIEW_H, initScripts));
            case "safari" -> new BrowserSession(BiDiBackends.safari(port, binaryOverride, log, confirmLaunch, VIEW_W,
                                            VIEW_H, initScripts));
            default ->
                throw new IllegalArgumentException("unknown browser '" + browser + "' (use chrome|firefox|safari)");
        };
    }

    /** Navigates to a URL in the real browser and returns the rendered page as markdown. */
    public synchronized String visit(String url) throws Exception {
        cancelled = false;
        lastUseNanos = System.nanoTime();
        backend.start();
        backend.navigate(normalizeUrl(url)); // returns once the new document has loaded (event-driven in the backend)
        dismissConsent();
        loadSettle();
        String reader = readerArticle(); // tier 1: Mozilla Readability ("Firefox reader mode"), clean articles
        if (!reader.isBlank())
            return reader;
        String md = str(evaluate(PAGE_EXTRACT_JS)); // tier 2: visible-only DOM walk
        if (md.isBlank())
            md = WebfetchTool.htmlToMarkdown(fullDomHtml()); // tier 3: whole rendered DOM
        return md;
    }

    /**
     * Extracts a PDF's text layer as markdown using the bundled PDF.js, run in the real browser; digital (text-layer)
     * PDFs only; a scanned/image-only PDF has no text layer and yields little. {@code base64} is the raw PDF bytes. The
     * lib + worker are injected via {@link #evaluate} and the parsing happens in-page, so nothing is linked into the
     * native image. Returns a placeholder string (never throws on a bad PDF) so the caller can fall back gracefully.
     */
    public synchronized String extractPdf(String base64) throws Exception {
        String lib = pdfLibJs();
        if (lib.isBlank())
            return "[pdf.js unavailable · it is downloaded at build time; see src/main/resources/browser/README.md]";
        cancelled = false;
        backend.start();
        backend.navigate("about:blank");
        evaluate(lib); // defines the PDF.js UMD global
        evaluate("globalThis.__pdfWorker=" + Util.MAPPER.writeValueAsString(pdfWorkerJs())); // Blob-worker source
        evaluate("globalThis.__pdfB64=" + Util.MAPPER.writeValueAsString(base64));
        return str(evaluate(EXTRACT_PDF_JS));
    }

    /** Runs Mozilla Readability on a clone of the RENDERED DOM → markdown; "" when the page isn't an article. */
    private String readerArticle() throws Exception {
        JsonNode art = evaluate(READER_EXTRACT_JS);
        if (art == null || !art.isObject() || str(art.path("content")).isBlank())
            return "";
        String title = str(art.path("title")), byline = str(art.path("byline"));
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank())
            sb.append("# ").append(title).append("\n\n");
        if (!byline.isBlank())
            sb.append("_").append(byline).append("_\n\n");
        return sb.append(currentUrl()).append("\n\n").append(WebfetchTool.htmlToMarkdown(str(art.path("content"))))
                                        .toString();
    }

    /** Runs a Google search in the real browser and returns the result links (+ a text snapshot) as markdown. */
    public synchronized String search(String query) throws Exception {
        cancelled = false;
        lastUseNanos = System.nanoTime();
        backend.start();
        backend.navigate(googleSearchUrl(query)); // returns once loaded (event-driven in the backend)
        dismissConsent();
        loadSettle();
        String results = str(evaluate(SEARCH_EXTRACT_JS));
        // DOM changed / blocked → fall back to the generic page conversion
        String body = !results.isBlank() ? results : WebfetchTool.htmlToMarkdown(fullDomHtml());
        return "# Search: " + query + "\n\n" + body;
    }

    /**
     * Triggers lazy-loaded content (adaptive scroll, only on long pages) then waits until the visible text stops
     * growing, so dynamic/SPA pages and search/feed results are fully present before extraction (ds4's behavior).
     */
    private void loadSettle() throws Exception {
        int innerH = evaluate("innerHeight").asInt(VIEW_H);
        if (evaluate("document.body ? document.body.scrollHeight : 0").asInt(0) > innerH * 1.35) {
            int lastH = -1;
            for (int i = 0; i < SCROLL_STEPS && !cancelled; i++) {
                evaluate("window.scrollBy(0, Math.round(innerHeight * 0.85))");
                Thread.sleep(timings.scrollStepMs());
                int h = evaluate("document.body ? document.body.scrollHeight : 0").asInt(0);
                if (h == lastH)
                    break; // page stopped growing → done loading
                lastH = h;
            }
            evaluate("window.scrollTo(0, 0)");
        }
        int last = -1, stable = 0;
        long deadline = System.nanoTime() + timings.stabilityCapMs() * 1_000_000L;
        while (before(deadline) && stable < timings.stableReads() && !cancelled) {
            int len = evaluate("((document.body && document.body.innerText) || '').length").asInt(0);
            if (len == last)
                stable++;
            else {
                stable = 0;
                last = len;
            }
            Thread.sleep(timings.stabilityPollMs());
        }
    }

    private void dismissConsent() {
        try {
            evaluate(CONSENT_JS);
            cancellableSleep(timings.consentMs());
        } catch (Exception ignored) {}
    }

    /**
     * Sleep up to {@code totalMs}, waking early if the session is {@link #cancelled} (close()), so a teardown mid-wait
     * isn't blocked. Mirrors the cancellation checks in the settle loops.
     */
    private void cancellableSleep(long totalMs) throws InterruptedException {
        for (long slept = 0; slept < totalMs && !cancelled; slept += 100)
            Thread.sleep(Math.min(100, totalMs - slept));
    }

    private String fullDomHtml() throws Exception {
        return str(evaluate("document.documentElement.outerHTML.slice(0," + MAX_HTML + ")"));
    }

    private JsonNode evaluate(String js) throws Exception {
        return backend.evaluate(js);
    }

    /**
     * Closes the backend (socket + any launched browser). Intentionally NOT synchronized: it trips {@link #cancelled}
     * so a call mid-navigate aborts promptly rather than making shutdown wait out the call timeout.
     */
    public void close() {
        cancelled = true;
        idleReaper.shutdownNow();
        backend.close();
    }

    private String currentUrl() {
        try {
            return str(evaluate("location.href"));
        } catch (Exception e) {
            return "";
        }
    }

    // ---- pure helpers (unit-testable) ----------------------------------------------------------------------

    private static boolean before(long deadlineNanos) {
        return System.nanoTime() < deadlineNanos;
    }

    static String str(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? "" : n.asText();
    }

    static String googleSearchUrl(String query) {
        return "https://www.google.com/search?hl=en&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    static String normalizeUrl(String url) {
        if (url == null || url.isBlank())
            throw new IllegalArgumentException("no url");
        String u = url.strip();
        if (!u.matches("(?i)^[a-z]+://.*"))
            u = "https://" + u;
        // http(s) only: a real, JS-running browser must not be steered (e.g. by prompt injection in page content)
        // into file:// (arbitrary local-file read) or other schemes.
        if (!u.matches("(?i)^https?://.*"))
            throw new IllegalArgumentException("only http/https URLs are allowed: " + url);
        // SSRF guard, mirroring webfetch: block loopback / link-local (incl. 169.254.169.254 cloud metadata) /
        // private / wildcard hosts. The browser doesn't force https, so the metadata endpoint would otherwise be
        // reachable; legitimate local services go through the bash tool.
        String host;
        try {
            host = URI.create(u).getHost();
        } catch (RuntimeException e) {
            host = null;
        }
        if (host == null || Util.isInternalAddress(host))
            throw new IllegalArgumentException("refusing to navigate to an internal/invalid address: " + url);
        return u;
    }

    // ---- injected extraction / consent scripts -----------------------------------------------------------

    private static String loadResource(String path) {
        try (var in = BrowserSession.class.getResourceAsStream(path)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // The injected JS lives as raw resources under src/main/resources/browser/ (alongside the vendored Mozilla
    // Readability.js, the engine behind Firefox Reader View, Apache-2.0). Readability is injected once per page (as an
    // init script the backend applies on every new document); reader.js then runs it on a clone of the rendered DOM.
    private static final String READABILITY_JS = loadResource("/browser/Readability.js");
    private static final String READER_EXTRACT_JS = loadResource("/browser/reader.js");
    private static final String PAGE_EXTRACT_JS = loadResource("/browser/extract-page.js");
    private static final String SEARCH_EXTRACT_JS = loadResource("/browser/extract-search.js");
    private static final String CONSENT_JS = loadResource("/browser/dismiss-consent.js");
    private static final String EXTRACT_PDF_JS = loadResource("/browser/extract-pdf.js");

    // PDF.js (lib + worker) is large and downloaded at build time, so load it lazily so a browse session that never
    // reads
    // a PDF doesn't hold ~1.5 MB, and so its absence (e.g. an offline first build) can't break class loading.
    private static volatile String pdfLib;
    private static volatile String pdfWorker;

    private static String pdfLibJs() {
        var v = pdfLib;
        if (v == null)
            pdfLib = v = loadResource("/browser/pdf.min.js");
        return v;
    }

    private static String pdfWorkerJs() {
        var v = pdfWorker;
        if (v == null)
            pdfWorker = v = loadResource("/browser/pdf.worker.min.js");
        return v;
    }
}
