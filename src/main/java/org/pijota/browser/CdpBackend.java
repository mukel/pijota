package org.pijota.browser;

import org.pijota.util.Executables;
import org.pijota.util.Platform;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * {@link BrowserBackend} over the Chrome DevTools Protocol, driving a real (visible) Chrome/Chromium. A dedicated
 * persistent profile is launched lazily (after a confirmation) and reused; a dropped socket reconnects on the next
 * call.
 */
public final class CdpBackend implements BrowserBackend {

    private static final long LOAD_TIMEOUT_MS = 25_000; // cap on the wait for the new document's load event
    // How long a freshly spawned Chrome gets to open its DevTools port. Its own constant, not the socket's connect
    // timeout: this waits on a process starting, that waits on a websocket handshake, and they are only the same
    // number by coincidence. BiDiBackend names the equivalent DRIVER_TIMEOUT_MS.
    private static final long CHROME_TIMEOUT_MS = 15_000;

    private final int port;
    private final Path profileDir;
    private final String chromeOverride;
    private final Consumer<String> log;
    private final Callable<Boolean> confirmLaunch; // asked once before SPAWNING a visible window
    private final boolean headless;
    private final int viewportW;
    private final int viewportH;
    private final List<String> initScripts; // run on every new document (e.g. define Readability)

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final WsJsonRpc rpc = new WsJsonRpc(http);
    private final LaunchedProcess chrome = new LaunchedProcess(); // the Chrome we launched (absent when we attached)

    private volatile boolean connected; // attached + CDP domains enabled (cleared on drop → next call reconnects)

    private final Object loadLock = new Object();
    private boolean loaded; // set by the Page.loadEventFired event; reset before each navigation (guarded by loadLock)

    public CdpBackend(int port, Path profileDir, String chromeOverride, Consumer<String> log,
                                    Callable<Boolean> confirmLaunch, int viewportW, int viewportH,
                                    List<String> initScripts) {
        this(port, profileDir, chromeOverride, log, confirmLaunch, false, viewportW, viewportH, initScripts);
    }

    public CdpBackend(int port, Path profileDir, String chromeOverride, Consumer<String> log,
                                    Callable<Boolean> confirmLaunch, boolean headless, int viewportW, int viewportH,
                                    List<String> initScripts) {
        this.port = port;
        this.profileDir = profileDir;
        this.chromeOverride = chromeOverride;
        this.log = log != null ? log : msg -> {};
        this.confirmLaunch = confirmLaunch != null ? confirmLaunch : () -> true;
        this.headless = headless;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.initScripts = initScripts != null ? initScripts : List.of();
        this.rpc.onDrop(() -> connected = false); // a dropped/stalled socket → reconnect on the next call
        this.rpc.onEvent(this::onCdpEvent); // react to lifecycle events (await load) instead of polling readyState
    }

    /**
     * Records {@code Page.loadEventFired} so {@link #navigate} can block until the new document has loaded. This event
     * is main-frame-scoped on a page target, so matching by method name is sufficient; the {@code loaded} flag is armed
     * (reset) under {@code loadLock} before each navigation so a prior page's event can't be mistaken for this one.
     */
    private void onCdpEvent(JsonNode frame) {
        if ("Page.loadEventFired".equals(frame.path("method").asText()))
            synchronized (loadLock) {
                loaded = true;
                loadLock.notifyAll();
            }
    }

    @Override
    public boolean isAlive() {
        return connected && rpc.isConnected() && chrome.aliveOrAbsent();
    }

    @Override
    public void start() throws Exception {
        if (isAlive())
            return;
        // Reuse a DevTools already on the port (a stale pijota-Chrome from a crashed run, or one launched earlier whose
        // socket merely dropped) instead of spawning a duplicate. Only confirm + spawn when nothing is there.
        if (!devtoolsAlive()) {
            if (!headless && !Boolean.TRUE.equals(confirmLaunch.call()))
                throw new IllegalStateException("browser launch declined");
            launchChrome();
            waitForDevtools();
        }
        rpc.connect(pageWsUrl());
        rpc.call("Page.enable", null);
        rpc.call("Runtime.enable", null);
        ObjectNode metrics = Util.MAPPER.createObjectNode();
        metrics.put("width", viewportW);
        metrics.put("height", viewportH);
        metrics.put("deviceScaleFactor", 1);
        metrics.put("mobile", false);
        rpc.call("Emulation.setDeviceMetricsOverride", metrics); // deterministic viewport for visibility-based
                                                                 // extraction
        for (String js : initScripts) {
            if (js == null || js.isBlank())
                continue;
            ObjectNode src = Util.MAPPER.createObjectNode();
            src.put("source", js);
            rpc.call("Page.addScriptToEvaluateOnNewDocument", src);
        }
        connected = true;
    }

    @Override
    public void navigate(String url) throws Exception {
        synchronized (loadLock) {
            loaded = false; // arm before issuing the navigation so we can't miss the event
        }
        ObjectNode p = Util.MAPPER.createObjectNode();
        p.put("url", url);
        JsonNode nav = rpc.call("Page.navigate", p);
        String navErr = nav.path("errorText").asText("");
        if (!navErr.isBlank() && !"net::ERR_ABORTED".equals(navErr)) // ERR_ABORTED is normal on redirects/downloads
            throw new IOException("could not load " + url + " (" + navErr + ")");
        awaitLoad(); // Page.navigate returns on commit; block until the new document's load event (like BiDi
                     // wait:"complete"). On timeout we proceed anyway; extraction still works on whatever loaded.
    }

    /** Blocks until {@code Page.loadEventFired} for the in-flight navigation, or {@link #LOAD_TIMEOUT_MS} elapses. */
    private void awaitLoad() throws InterruptedException {
        long deadline = System.nanoTime() + LOAD_TIMEOUT_MS * 1_000_000L;
        synchronized (loadLock) {
            while (!loaded && rpc.isConnected()) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0)
                    return; // timed out → fall back to extracting the partially-loaded page
                loadLock.wait(Math.min(remainingMs, 200)); // 200ms cap so close() aborts the wait promptly
            }
        }
    }

    @Override
    public JsonNode evaluate(String js) throws Exception {
        ObjectNode p = Util.MAPPER.createObjectNode();
        p.put("expression", js);
        p.put("returnByValue", true);
        p.put("awaitPromise", true);
        return rpc.call("Runtime.evaluate", p).path("result").path("value");
    }

    @Override
    public void close() {
        connected = false;
        rpc.close();
        chrome.kill();
    }

    // ---- launch / attach -----------------------------------------------------------------------------------

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
            throw new IllegalStateException("Chrome/Chromium not found. Install it or set PIJOTA_BROWSER_BIN=/path/to/chrome.");
        Files.createDirectories(profileDir);
        var cmd = launchCommand(bin);
        log.accept("launching " + (headless ? "headless " : "") + bin.getFileName() + " (debug port " + port
                                        + ", profile " + profileDir + ")");
        chrome.start(cmd);
    }

    /** Chrome argv, split from process creation so headless mode has a browser-free regression check. */
    List<String> launchCommand(Path bin) {
        var cmd = new ArrayList<>(List.of(bin.toString(), "--remote-debugging-port=" + port,
                                        "--remote-debugging-address=127.0.0.1", "--remote-allow-origins=*",
                                        "--user-data-dir=" + profileDir, "--no-first-run", "--no-default-browser-check",
                                        "--disable-sync", "--mute-audio", "--disable-features=Translate",
                                        "--password-store=basic", // avoid an OS keyring/keychain unlock prompt blocking
                                                                  // startup
                                        "about:blank"));
        if (headless)
            cmd.add(1, "--headless=new");
        if (Platform.isMac())
            cmd.add(1, "--use-mock-keychain");
        if ("root".equals(System.getProperty("user.name")))
            cmd.add(1, "--no-sandbox");
        return cmd;
    }

    private void waitForDevtools() throws Exception {
        chrome.awaitReady(this::devtoolsAlive, CHROME_TIMEOUT_MS, "Chrome DevTools (port " + port + ")",
                                        "the port may be in use or the profile locked");
    }

    /** The WebSocket URL of a usable page target, reusing an existing one, else opening a fresh tab. */
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

    // ---- Chrome discovery (pure, unit-testable) ------------------------------------------------------------

    /** Resolves the Chrome/Chromium binary: explicit override → common install paths → {@code PATH}. */
    static Path findChrome(String override) {
        if (override != null && !override.isBlank())
            return Path.of(override);
        var candidates = new ArrayList<String>();
        if (Platform.isWindows()) {
            for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "LocalAppData")) {
                String base = System.getenv(env);
                if (base != null && !base.isBlank()) {
                    candidates.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe").toString());
                    candidates.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe").toString());
                }
            }
            candidates.addAll(List.of("chrome", "chromium", "msedge")); // PATH, resolved via PATHEXT
        } else if (Platform.isMac()) {
            candidates.addAll(List.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                                            "/Applications/Chromium.app/Contents/MacOS/Chromium",
                                            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                                            "/opt/homebrew/bin/chromium", "/usr/local/bin/chromium",
                                            "/opt/homebrew/bin/google-chrome", "/usr/local/bin/google-chrome",
                                            "google-chrome", "chromium", "chrome", "msedge"));
        } else {
            candidates.addAll(List.of("/usr/bin/google-chrome-stable", "/usr/bin/google-chrome", "/usr/bin/chromium",
                                            "/usr/bin/chromium-browser", "/snap/bin/chromium",
                                            "/opt/google/chrome/chrome",
                                            // …then bare names on PATH
                                            "google-chrome-stable", "google-chrome", "chromium", "chromium-browser",
                                            "chrome"));
        }
        return Executables.findAny(candidates);
    }
}
