package ai.pijota.browser;

import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * {@link BrowserBackend} over the W3C WebDriver BiDi protocol — drives Firefox, Safari or Chrome through their
 * WebDriver "driver" (geckodriver / safaridriver / chromedriver). The driver launches the browser; we POST
 * {@code /session} (requesting {@code webSocketUrl}) to get a BiDi WebSocket and then speak BiDi over the shared
 * {@link WsJsonRpc}. The five {@link BrowserBackend} primitives map onto {@code browsingContext.navigate} and
 * {@code script.evaluate}; the typed BiDi remote values are unwrapped back to the plain JSON the extraction layer
 * expects by {@link #bidiToJson}.
 */
public final class BiDiBackend implements BrowserBackend {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BiDiBackend.class);
    private static final long DRIVER_TIMEOUT_MS = 20_000;
    private static final long CONNECT_TIMEOUT_MS = 15_000;
    private static final long CALL_TIMEOUT_MS = 30_000;
    private static final int MAX_OBJECT_DEPTH = 10; // deep-serialize returned objects/arrays (DOM nodes never returned)

    private final List<String> driverCommand; // how to launch the driver on driverPort
    private final int driverPort;
    private final ObjectNode alwaysMatch; // WebDriver capabilities (webSocketUrl is added automatically)
    private final Consumer<String> log;
    private final Callable<Boolean> confirmLaunch;
    private final int viewportW;
    private final int viewportH;
    private final List<String> initScripts;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final WsJsonRpc rpc = new WsJsonRpc(http, CONNECT_TIMEOUT_MS, CALL_TIMEOUT_MS);
    private final LaunchedProcess driver = new LaunchedProcess(); // the WebDriver process we launched

    private volatile String sessionId;
    private volatile String context; // the root browsing-context id (navigate/evaluate target)
    private volatile boolean connected;

    public BiDiBackend(List<String> driverCommand, int driverPort, ObjectNode alwaysMatch, Consumer<String> log,
                                    Callable<Boolean> confirmLaunch, int viewportW, int viewportH,
                                    List<String> initScripts) {
        this.driverCommand = driverCommand;
        this.driverPort = driverPort;
        this.alwaysMatch = alwaysMatch != null ? alwaysMatch : Util.MAPPER.createObjectNode();
        this.log = log != null ? log : msg -> {};
        this.confirmLaunch = confirmLaunch != null ? confirmLaunch : () -> true;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.initScripts = initScripts != null ? initScripts : List.of();
        this.rpc.onDrop(() -> connected = false);
    }

    @Override
    public boolean isAlive() {
        return connected && rpc.isConnected() && driver.aliveOrAbsent();
    }

    @Override
    public void start() throws Exception {
        if (isAlive())
            return;
        if (!driver.running()) {
            if (!Boolean.TRUE.equals(confirmLaunch.call()))
                throw new IllegalStateException("browser launch declined");
            launchDriver();
            waitForDriver();
        }
        JsonNode session = openSession();
        sessionId = session.path("value").path("sessionId").asText("");
        String wsUrl = webSocketUrlFrom(session);
        if (wsUrl.isBlank())
            throw new IllegalStateException("WebDriver did not return a BiDi webSocketUrl (driver lacks BiDi support?)");
        rpc.connect(wsUrl);
        context = firstContext(rpc.call("browsingContext.getTree", Util.MAPPER.createObjectNode()));
        if (context.isBlank())
            throw new IllegalStateException("no browsing context");
        applyViewport();
        applyInitScripts();
        connected = true;
    }

    @Override
    public void navigate(String url) throws Exception {
        ObjectNode p = Util.MAPPER.createObjectNode();
        p.put("context", context);
        p.put("url", url);
        // Block until the document has loaded. CDP's Page.navigate returns once navigation COMMITS, but BiDi's
        // wait:"none" returns before the URL even leaves about:blank — so BrowserSession's poll loop would see the
        // initial about:blank already "complete" and extract a blank page. "complete" returns on the loaded document.
        p.put("wait", "complete");
        rpc.call("browsingContext.navigate", p);
    }

    @Override
    public JsonNode evaluate(String js) throws Exception {
        ObjectNode target = Util.MAPPER.createObjectNode();
        target.put("context", context);
        ObjectNode serialization = Util.MAPPER.createObjectNode();
        serialization.put("maxObjectDepth", MAX_OBJECT_DEPTH);
        serialization.put("maxDomDepth", 0);
        ObjectNode p = Util.MAPPER.createObjectNode();
        p.put("expression", js);
        p.set("target", target);
        p.put("awaitPromise", true);
        p.put("resultOwnership", "none");
        p.set("serializationOptions", serialization);
        JsonNode r = rpc.call("script.evaluate", p);
        if ("exception".equals(r.path("type").asText())) {
            // A JS error → no value (matches CDP's empty-on-error). Log it: the extraction scripts catch their own
            // errors, so a surfaced exception here points at a real eval/protocol problem worth seeing in the console.
            LOG.warn("BiDi script.evaluate exception: {}", r.path("exceptionDetails").path("text").asText(""));
            return NullNode.getInstance();
        }
        return bidiToJson(r.path("result"));
    }

    @Override
    public void close() {
        connected = false;
        try {
            if (sessionId != null && rpc.isConnected())
                rpc.call("session.end", Util.MAPPER.createObjectNode());
        } catch (Exception ignored) {
            // best-effort; we kill the driver below regardless
        }
        rpc.close();
        driver.kill();
    }

    // ---- BiDi setup ----------------------------------------------------------------------------------------

    private void applyViewport() throws Exception {
        ObjectNode viewport = Util.MAPPER.createObjectNode();
        viewport.put("width", viewportW);
        viewport.put("height", viewportH);
        ObjectNode p = Util.MAPPER.createObjectNode();
        p.put("context", context);
        p.set("viewport", viewport);
        try {
            rpc.call("browsingContext.setViewport", p);
        } catch (Exception e) {
            // Some drivers (older safaridriver) don't implement setViewport — best-effort: resize via script instead.
            log.accept("setViewport unsupported (" + e.getMessage() + "); falling back to window.resizeTo");
            evaluate("window.resizeTo(" + viewportW + "," + viewportH + ")");
        }
    }

    private void applyInitScripts() throws Exception {
        for (String js : initScripts) {
            if (js == null || js.isBlank())
                continue;
            ObjectNode p = Util.MAPPER.createObjectNode();
            // Run the script with this===globalThis so UMD bundles (Readability) still attach their global.
            p.put("functionDeclaration", "function(){" + js + "}");
            rpc.call("script.addPreloadScript", p);
        }
    }

    // ---- driver process + /session bootstrap ---------------------------------------------------------------

    private void launchDriver() throws Exception {
        log.accept("launching webdriver: " + String.join(" ", driverCommand));
        driver.start(driverCommand);
    }

    private void waitForDriver() throws Exception {
        long deadline = System.nanoTime() + DRIVER_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                if (httpGet("/status") != null)
                    return;
            } catch (Exception ignored) {
                // driver not up yet
            }
            Thread.sleep(150);
        }
        throw new IllegalStateException("WebDriver did not come up on port " + driverPort);
    }

    /** POSTs {@code /session} requesting a BiDi websocket and returns the parsed response. */
    private JsonNode openSession() throws Exception {
        ObjectNode caps = alwaysMatch.deepCopy();
        caps.put("webSocketUrl", true);
        ObjectNode capabilities = Util.MAPPER.createObjectNode();
        capabilities.set("alwaysMatch", caps);
        ObjectNode body = Util.MAPPER.createObjectNode();
        body.set("capabilities", capabilities);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(uri("/session")).timeout(Duration.ofSeconds(20))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(Util.MAPPER.writeValueAsString(body)))
                                        .build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = Util.MAPPER.readTree(resp.body());
        if (resp.statusCode() != 200)
            throw new IllegalStateException("WebDriver /session failed: "
                                            + root.path("value").path("message").asText(resp.body()));
        return root;
    }

    private String httpGet(String path) throws Exception {
        HttpResponse<String> resp = http.send(
                                        HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3)).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + driverPort + path);
    }

    // ---- pure helpers (unit-testable) ----------------------------------------------------------------------

    /** The BiDi WebSocket URL from a {@code /session} response, or "" if absent. */
    static String webSocketUrlFrom(JsonNode sessionResponse) {
        return sessionResponse.path("value").path("capabilities").path("webSocketUrl").asText("");
    }

    /** The id of the first (root) browsing context from a {@code browsingContext.getTree} result. */
    static String firstContext(JsonNode getTreeResult) {
        JsonNode contexts = getTreeResult.path("contexts");
        return contexts.isArray() && !contexts.isEmpty() ? contexts.get(0).path("context").asText("") : "";
    }

    /**
     * Converts a BiDi {@code RemoteValue} into the plain JSON the extraction layer expects (the equivalent of CDP's
     * {@code returnByValue}). Primitives unwrap to their value; arrays and objects recurse. Non-serializable special
     * numbers (NaN/Infinity) and unsupported types (node/regexp/…) collapse to null, which the callers treat as "no
     * value" via {@code str(...)}.
     */
    static JsonNode bidiToJson(JsonNode rv) {
        if (rv == null || rv.isMissingNode())
            return NullNode.getInstance();
        String type = rv.path("type").asText("");
        switch (type) {
            case "string":
                return Util.MAPPER.getNodeFactory().textNode(rv.path("value").asText(""));
            case "boolean":
                return Util.MAPPER.getNodeFactory().booleanNode(rv.path("value").asBoolean());
            case "number": {
                JsonNode v = rv.path("value");
                return v.isNumber() ? v : NullNode.getInstance(); // "NaN"/"Infinity"/"-0" arrive as strings → null
            }
            case "bigint":
                return Util.MAPPER.getNodeFactory().textNode(rv.path("value").asText("")); // keep precision as text
            case "null":
            case "undefined":
                return NullNode.getInstance();
            case "array": {
                ArrayNode arr = Util.MAPPER.createArrayNode();
                for (JsonNode el : rv.path("value"))
                    arr.add(bidiToJson(el));
                return arr;
            }
            case "object": {
                ObjectNode obj = Util.MAPPER.createObjectNode();
                for (JsonNode pair : rv.path("value")) { // value is [[key, RemoteValue], ...]
                    if (!pair.isArray() || pair.size() < 2)
                        continue;
                    JsonNode k = pair.get(0);
                    String key = k.isTextual() ? k.asText() : bidiToJson(k).asText("");
                    obj.set(key, bidiToJson(pair.get(1)));
                }
                return obj;
            }
            default:
                return NullNode.getInstance(); // node/date/regexp/map/set/window/… not needed by the extractors
        }
    }
}
