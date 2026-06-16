package ai.pijota.browser;

import ai.pijota.tools.WebfetchTool;
import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Drives a real (visible) Chrome over the Chrome DevTools Protocol so the model can read JS-rendered pages and search
 * results — the way a person's browser sees the web, not a blocked headless fetch. JDK-only: {@code java.net.http} for
 * the CDP HTTP endpoints + WebSocket, Jackson for JSON-RPC; rendered HTML falls back to the shared
 * {@link WebfetchTool#htmlToMarkdown}.
 *
 * <p>
 * Opaque to callers: they get markdown back from {@link #visit} / {@link #search}; all CDP mechanics and the Chrome
 * process stay inside this class. A dedicated persistent profile under {@code ~/.pijota/browser} is launched lazily
 * (after a confirmation) and reused across calls; a dropped socket is transparently reconnected on the next call. Calls
 * are serialized — one page, one op at a time — so concurrent tool invocations can't race the protocol.
 */
public final class BrowserSession {

    private static final long CONNECT_TIMEOUT_MS = 15_000;
    private static final long CALL_TIMEOUT_MS = 30_000;
    private static final long LOAD_TIMEOUT_MS = 25_000;
    private static final long STABILITY_TIMEOUT_MS = 6_000; // cap on the "text stopped growing" settle wait
    private static final int SCROLL_STEPS = 12; // max progressive scrolls to trigger lazy-loaded content
    private static final int VIEW_W = 1365, VIEW_H = 900; // emulated viewport (matches ds4) so layout is deterministic
    private static final int MAX_HTML = 1_500_000; // cap the rendered DOM pulled back over CDP (fallback path only)

    private final int port;
    private final Path profileDir;
    private final String chromeOverride;
    private final Consumer<String> log;
    private final Callable<Boolean> confirmLaunch; // asked once before SPAWNING a visible window (ds4's confirm cb)

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger nextId = new AtomicInteger();
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownHook = new AtomicBoolean();

    private volatile Process chrome; // the Chrome WE launched (null when we attached to an already-running one)
    private volatile WebSocket pageWs;
    private volatile boolean connected; // attached + CDP domains enabled (cleared on drop → next call reconnects)
    private volatile boolean cancelled; // tripped by close() to interrupt the in-flight navigate/settle loops

    public BrowserSession(int port, Path profileDir, String chromeOverride, Consumer<String> log,
                                    Callable<Boolean> confirmLaunch) {
        this.port = port;
        this.profileDir = profileDir;
        this.chromeOverride = chromeOverride;
        this.log = log != null ? log : msg -> {};
        this.confirmLaunch = confirmLaunch != null ? confirmLaunch : () -> true;
    }

    /** Attached, CDP-enabled, and the Chrome we launched (if any) is still alive. */
    private boolean ready() {
        return connected && pageWs != null && (chrome == null || chrome.isAlive());
    }

    /** Navigates to a URL in the real browser and returns the rendered page as markdown. */
    public synchronized String visit(String url) throws Exception {
        cancelled = false;
        ensureReady();
        navigate(normalizeUrl(url));
        dismissConsent();
        loadSettle();
        String reader = readerArticle(); // tier 1: Mozilla Readability ("Firefox reader mode") — clean articles
        if (!reader.isBlank())
            return reader;
        String md = str(evaluate(PAGE_EXTRACT_JS)); // tier 2: visible-only DOM walk
        if (md.isBlank())
            md = WebfetchTool.htmlToMarkdown(fullDomHtml()); // tier 3: whole rendered DOM
        return md;
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
        ensureReady();
        navigate(googleSearchUrl(query));
        dismissConsent();
        loadSettle();
        String results = str(evaluate(SEARCH_EXTRACT_JS));
        // DOM changed / blocked → fall back to the generic page conversion
        String body = !results.isBlank() ? results : WebfetchTool.htmlToMarkdown(fullDomHtml());
        return "# Search: " + query + "\n\n" + body;
    }

    // ---- CDP lifecycle -------------------------------------------------------------------------------------

    private void ensureReady() throws Exception {
        if (ready())
            return;
        // Reuse a DevTools already on the port (a stale pijota-Chrome from a crashed run, or one launched earlier
        // whose socket merely dropped) instead of spawning a duplicate. Only confirm + spawn when nothing is there.
        if (!devtoolsAlive()) {
            if (!Boolean.TRUE.equals(confirmLaunch.call()))
                throw new IllegalStateException("browser launch declined");
            launchChrome();
            waitForDevtools();
        }
        connect(pageWsUrl());
        call("Page.enable", null);
        call("Runtime.enable", null);
        ObjectNode metrics = Util.MAPPER.createObjectNode();
        metrics.put("width", VIEW_W);
        metrics.put("height", VIEW_H);
        metrics.put("deviceScaleFactor", 1);
        metrics.put("mobile", false);
        call("Emulation.setDeviceMetricsOverride", metrics); // deterministic viewport for visibility-based extraction
        if (!READABILITY_JS.isBlank()) {
            ObjectNode reader = Util.MAPPER.createObjectNode();
            reader.put("source", READABILITY_JS);
            call("Page.addScriptToEvaluateOnNewDocument", reader); // define Readability on every page for reader mode
        }
        connected = true;
    }

    private boolean devtoolsAlive() {
        try {
            return httpGet("/json/version") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void launchChrome() throws Exception {
        Path bin = findChrome(chromeOverride);
        if (bin == null)
            throw new IllegalStateException("Chrome/Chromium not found. Set PIJOTA_CHROME=/path/to/chrome (or --chrome=...).");
        Files.createDirectories(profileDir);
        var cmd = new ArrayList<>(List.of(bin.toString(), "--remote-debugging-port=" + port,
                                        "--remote-debugging-address=127.0.0.1", "--remote-allow-origins=*",
                                        "--user-data-dir=" + profileDir, "--no-first-run", "--no-default-browser-check",
                                        "--disable-sync", "--mute-audio", "--disable-features=Translate",
                                        "--password-store=basic", // avoid an OS keyring/keychain unlock prompt blocking
                                                                  // startup
                                        "about:blank"));
        if (System.getProperty("os.name", "").toLowerCase().contains("mac"))
            cmd.add(1, "--use-mock-keychain");
        if ("root".equals(System.getProperty("user.name")))
            cmd.add(1, "--no-sandbox");
        log.accept("launching " + bin.getFileName() + " (debug port " + port + ", profile " + profileDir + ")");
        chrome = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Kill the Chrome we launched even if the JVM is terminated before onShutdown runs (SIGTERM); one hook/session.
        if (shutdownHook.compareAndSet(false, true))
            Runtime.getRuntime().addShutdownHook(new Thread(this::killLaunchedChrome, "pijota-browser-shutdown"));
    }

    private void waitForDevtools() throws Exception {
        long deadline = System.nanoTime() + CONNECT_TIMEOUT_MS * 1_000_000L;
        while (before(deadline)) {
            if (devtoolsAlive())
                return;
            Thread.sleep(150);
        }
        throw new IllegalStateException("Chrome DevTools did not come up on port " + port);
    }

    /** The WebSocket URL of a usable page target — reusing an existing one, else opening a fresh tab. */
    private String pageWsUrl() throws Exception {
        String list = httpGet("/json");
        if (list != null)
            for (JsonNode t : Util.MAPPER.readTree(list))
                if ("page".equals(t.path("type").asText()) && !t.path("webSocketDebuggerUrl").asText().isBlank())
                    return t.path("webSocketDebuggerUrl").asText();
        // no usable page target (e.g. the tab was closed) → open one (PUT for modern Chrome, GET for older)
        String created = httpNewTab();
        String ws = created == null ? "" : Util.MAPPER.readTree(created).path("webSocketDebuggerUrl").asText();
        if (ws.isBlank())
            throw new IllegalStateException("could not open a browser tab on port " + port);
        return ws;
    }

    private void connect(String wsUrl) throws Exception {
        pageWs = http.newWebSocketBuilder().buildAsync(URI.create(wsUrl), new Listener()).get(CONNECT_TIMEOUT_MS,
                                        TimeUnit.MILLISECONDS);
    }

    private void navigate(String url) throws Exception {
        String prev = currentUrl();
        ObjectNode p = Util.MAPPER.createObjectNode();
        p.put("url", url);
        JsonNode nav = call("Page.navigate", p);
        String navErr = str(nav.path("errorText"));
        if (!navErr.isBlank() && !"net::ERR_ABORTED".equals(navErr)) // ERR_ABORTED is normal on redirects/downloads
            throw new IOException("could not load " + url + " (" + navErr + ")");
        // Page.navigate returns when navigation STARTS; wait for the new document to actually commit + finish so we
        // never extract the previous page (the URL changing off `prev` marks the new document, redirects included).
        long deadline = System.nanoTime() + LOAD_TIMEOUT_MS * 1_000_000L;
        while (before(deadline) && !cancelled) {
            String href = str(evaluate("location.href"));
            boolean committed = !href.equals(prev) || "about:blank".equals(prev);
            if (committed && "complete".equals(str(evaluate("document.readyState"))))
                break;
            Thread.sleep(200);
        }
    }

    /**
     * Triggers lazy-loaded content (adaptive scroll, only on long pages) then waits until the visible text stops
     * growing — so dynamic/SPA pages and search/feed results are fully present before extraction (ds4's behavior).
     */
    private void loadSettle() throws Exception {
        int innerH = evaluate("innerHeight").asInt(VIEW_H);
        if (evaluate("document.body ? document.body.scrollHeight : 0").asInt(0) > innerH * 1.35) {
            int lastH = -1;
            for (int i = 0; i < SCROLL_STEPS && !cancelled; i++) {
                evaluate("window.scrollBy(0, Math.round(innerHeight * 0.85))");
                Thread.sleep(450);
                int h = evaluate("document.body ? document.body.scrollHeight : 0").asInt(0);
                if (h == lastH)
                    break; // page stopped growing → done loading
                lastH = h;
            }
            evaluate("window.scrollTo(0, 0)");
        }
        int last = -1, stable = 0;
        long deadline = System.nanoTime() + STABILITY_TIMEOUT_MS * 1_000_000L;
        while (before(deadline) && stable < 3 && !cancelled) {
            int len = evaluate("((document.body && document.body.innerText) || '').length").asInt(0);
            if (len == last)
                stable++;
            else {
                stable = 0;
                last = len;
            }
            Thread.sleep(250);
        }
    }

    private void dismissConsent() {
        try {
            evaluate(CONSENT_JS);
            Thread.sleep(300);
        } catch (Exception ignored) {}
    }

    // ---- CDP JSON-RPC --------------------------------------------------------------------------------------

    private String fullDomHtml() throws Exception {
        return str(evaluate("document.documentElement.outerHTML.slice(0," + MAX_HTML + ")"));
    }

    private JsonNode evaluate(String js) throws Exception {
        ObjectNode p = Util.MAPPER.createObjectNode();
        p.put("expression", js);
        p.put("returnByValue", true);
        p.put("awaitPromise", true);
        return call("Runtime.evaluate", p).path("result").path("value");
    }

    private JsonNode call(String method, ObjectNode params) throws Exception {
        WebSocket ws = pageWs;
        if (ws == null)
            throw new IllegalStateException("browser not connected");
        int id = nextId.incrementAndGet();
        var fut = new CompletableFuture<JsonNode>();
        pending.put(id, fut);
        try {
            ObjectNode req = Util.MAPPER.createObjectNode();
            req.put("id", id);
            req.put("method", method);
            if (params != null)
                req.set("params", params);
            ws.sendText(Util.MAPPER.writeValueAsString(req), true);
            // Poll so close() (which trips `cancelled` + fails pending) aborts within ~200ms instead of the full
            // timeout, and so a genuinely unresponsive socket forces a reconnect rather than cascading stalls.
            long deadline = System.nanoTime() + CALL_TIMEOUT_MS * 1_000_000L;
            while (true) {
                if (cancelled)
                    throw new IllegalStateException("browser session closed");
                try {
                    return fut.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    if (!before(deadline)) {
                        connected = false; // unresponsive socket → reconnect on the next call
                        throw te;
                    }
                }
            }
        } finally {
            pending.remove(id);
        }
    }

    /** Routes an incoming CDP frame to the waiting caller; events (no {@code id}) are ignored (we poll, not react). */
    void handleMessage(String json) {
        try {
            JsonNode n = Util.MAPPER.readTree(json);
            if (!n.hasNonNull("id"))
                return;
            CompletableFuture<JsonNode> fut = pending.remove(n.get("id").asInt());
            if (fut == null)
                return;
            if (n.has("error"))
                fut.completeExceptionally(new IllegalStateException("CDP error: " + n.get("error").toString()));
            else
                fut.complete(n.path("result"));
        } catch (Exception ignored) {}
    }

    /** Registers a pending request slot (test seam for the JSON-RPC routing). */
    CompletableFuture<JsonNode> awaitId(int id) {
        var fut = new CompletableFuture<JsonNode>();
        pending.put(id, fut);
        return fut;
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                handleMessage(msg);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            drop(ws, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            drop(ws, new IllegalStateException("CDP socket closed")); // crash / closed tab → reconnect next call
            return null;
        }

        private void drop(WebSocket ws, Throwable t) {
            if (ws == pageWs) // ignore stale events from a socket we already replaced on reconnect
                connected = false;
            failPending(t);
        }
    }

    /** Completes-and-removes every in-flight request atomically (so a request registered concurrently can't hang). */
    private void failPending(Throwable t) {
        for (Integer id : pending.keySet()) {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null)
                f.completeExceptionally(t);
        }
    }

    /**
     * Closes the socket and terminates the Chrome this session launched. Intentionally NOT synchronized: it trips
     * {@link #cancelled} and fails any in-flight request so a call mid-navigate aborts promptly rather than making
     * shutdown wait out the call timeout.
     */
    public void close() {
        cancelled = true;
        connected = false;
        WebSocket ws = pageWs;
        pageWs = null;
        if (ws != null)
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {}
        failPending(new IllegalStateException("session closed"));
        killLaunchedChrome();
    }

    private void killLaunchedChrome() {
        Process p = chrome;
        chrome = null;
        if (p == null || !p.isAlive())
            return;
        // Take down the whole tree: Chrome's renderer/GPU/zygote children would otherwise be orphaned by a forced
        // kill of the parent alone.
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    // ---- HTTP helpers --------------------------------------------------------------------------------------

    /** Sends a DevTools HTTP request; returns the body on 200, else null. */
    private String send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    private String httpGet(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3)).GET().build());
    }

    /** Opens a fresh tab via {@code /json/new} (PUT for modern Chrome; GET for older builds). */
    private String httpNewTab() throws Exception {
        String body = send(HttpRequest.newBuilder(uri("/json/new?about:blank")).timeout(Duration.ofSeconds(5))
                                        .PUT(HttpRequest.BodyPublishers.noBody()).build());
        return body != null ? body : httpGet("/json/new?about:blank");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
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
        return u;
    }

    /** Resolves the Chrome/Chromium binary: explicit override → common install paths → {@code PATH}. */
    static Path findChrome(String override) {
        if (override != null && !override.isBlank())
            return Path.of(override);
        var candidates = new ArrayList<Path>();
        for (String p : List.of("/usr/bin/google-chrome-stable", "/usr/bin/google-chrome", "/usr/bin/chromium",
                                        "/usr/bin/chromium-browser", "/snap/bin/chromium", "/opt/google/chrome/chrome",
                                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                                        "/Applications/Chromium.app/Contents/MacOS/Chromium"))
            candidates.add(Path.of(p));
        String path = System.getenv("PATH");
        if (path != null)
            for (String dir : path.split(java.io.File.pathSeparator))
                for (String name : List.of("google-chrome-stable", "google-chrome", "chromium", "chromium-browser",
                                                "chrome"))
                    candidates.add(Path.of(dir, name));
        return firstExecutable(candidates, Files::isExecutable);
    }

    static Path firstExecutable(List<Path> candidates, Predicate<Path> isExecutable) {
        for (Path p : candidates)
            if (isExecutable.test(p))
                return p;
        return null;
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
    // Readability.js — the engine behind Firefox Reader View, Apache-2.0). Readability is injected once per page
    // (Page.addScriptToEvaluateOnNewDocument in ensureReady); reader.js then runs it on a clone of the rendered DOM.
    private static final String READABILITY_JS = loadResource("/browser/Readability.js");
    private static final String READER_EXTRACT_JS = loadResource("/browser/reader.js");
    private static final String PAGE_EXTRACT_JS = loadResource("/browser/extract-page.js");
    private static final String SEARCH_EXTRACT_JS = loadResource("/browser/extract-search.js");
    private static final String CONSENT_JS = loadResource("/browser/dismiss-consent.js");
}
