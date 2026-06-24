package ai.pijota.web;

import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.PijotaSession;
import ai.pijota.config.PathDisplay;
import ai.pijota.llm.LlmClient;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.ToolLabels;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;

/**
 * A tiny web frontend for a {@link PijotaSession}: the chat <em>is</em> the conversation, served as a single page. The
 * agent's {@link AgentEvent} stream is pushed to the browser over Server-Sent Events; the browser POSTs prompts,
 * interrupts and dialog answers back. Built entirely on the JDK's {@link HttpServer} — no web framework, no extra
 * dependency.
 *
 * <p>
 * Security: this server lets the browser drive an agent that can run shell commands, so it binds to loopback
 * ({@code 127.0.0.1}) only and is never exposed on a routable interface. It is a personal, single-user control surface.
 */
public final class WebServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final long HEARTBEAT_SECONDS = 15; // SSE comment ping; a failed write prunes a closed browser tab
    private static final long STREAM_COALESCE_MS = 60; // cap streaming-update fan-out; each partial carries full
                                                       // content

    private volatile long lastStreamAt;
    private volatile List<String> lastStatusLines = List.of();
    private volatile Map<String, Object> lastStats = Map.of();

    public static final int DEFAULT_PORT = 8420;
    public static final String DEFAULT_HOST = "127.0.0.1";

    private final PijotaSession session;
    private final String host;
    private final int port;
    private final LlmClient llm; // current model + usage, read live for the header (same accessors the TUI uses)
    private final Supplier<List<String>> statusLines; // keyed statuses + registered status/footer lines (TUI parity)
    private final List<SlashCommand> commands;
    private final WebUiContext ui;
    // When non-null, every request must carry this token (cookie or ?t=) — required once the server binds past
    // loopback.
    private final String authToken;
    private final Consumer<AgentEvent> eventListener = this::onEvent; // stored so close() can remove it
    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat = Executors
                                    .newSingleThreadScheduledExecutor(daemon("pijota-web-hb"));

    private HttpServer http;

    /**
     * @param commands
     *            the registered slash commands the browser may run — all frontend-agnostic (extension commands plus
     *            generic built-ins like {@code /model} from {@code CoreCommandsExtension}). Terminal-bound TUI commands
     *            are not in this set.
     */
    public WebServer(PijotaSession session, String host, int port, LlmClient llm, Supplier<List<String>> statusLines,
                                    List<SlashCommand> commands) {
        this(session, host, port, llm, statusLines, commands, null);
    }

    /**
     * @param authToken
     *            when non-blank, every request must present it (via {@code ?t=} or the {@code pijota_rc} cookie).
     */
    public WebServer(PijotaSession session, String host, int port, LlmClient llm, Supplier<List<String>> statusLines,
                                    List<SlashCommand> commands, String authToken) {
        this.session = session;
        this.host = host == null || host.isBlank() ? DEFAULT_HOST : host;
        this.port = port;
        this.llm = llm;
        this.statusLines = statusLines;
        this.commands = List.copyOf(commands);
        this.ui = new WebUiContext(this::broadcast);
        this.authToken = authToken == null || authToken.isBlank() ? null : authToken;
    }

    /**
     * Parses the {@code --web[=HOST:PORT]} flag value into a bind address. Forms: empty/{@code "true"} → loopback +
     * default port; a bare number → that port on loopback; {@code HOST:PORT} → that host and port; a bare host → that
     * host on the default port. Invalid ports fall back to the default. The host defaults to loopback so the
     * shell-capable UI is never accidentally exposed.
     */
    public static Address parseAddress(String flagValue) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (flagValue != null && !flagValue.isBlank() && !"true".equals(flagValue)) {
            String value = flagValue.trim();
            int colon = value.lastIndexOf(':');
            if (colon >= 0) { // HOST:PORT (or :PORT)
                String h = value.substring(0, colon).trim();
                if (!h.isEmpty())
                    host = h;
                port = parsePort(value.substring(colon + 1), port);
            } else if (value.chars().allMatch(Character::isDigit)) { // bare PORT
                port = parsePort(value, port);
            } else { // bare HOST
                host = value;
            }
        }
        return new Address(host, port);
    }

    private static int parsePort(String text, int fallback) {
        try {
            int p = Integer.parseInt(text.trim());
            return p > 0 && p <= 65535 ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A resolved web bind address. {@link #isLoopback()} gates the "exposed UI" warning. */
    public record Address(String host, int port) {
        public boolean isLoopback() {
            return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("::1")
                                            || host.equals("[::1]");
        }
    }

    /** The agent's interactive-callback surface (confirm/select/prompt) bridged to the browser. */
    public WebUiContext ui() {
        return ui;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/events", this::serveEvents);
        http.createContext("/prompt", this::handlePrompt);
        http.createContext("/interrupt", this::handleInterrupt);
        http.createContext("/answer", this::handleAnswer);
        http.createContext("/commands", this::handleCommands);
        http.createContext("/vendor/", this::serveVendor); // version-pinned UI libs, served locally (no CDN)
        http.createContext("/", this::serveIndex); // catch-all: index for "/", 404 otherwise
        http.setExecutor(Executors.newCachedThreadPool(daemon("pijota-web")));
        // guard()ed so a single failure can't cancel the periodic task (scheduleAtFixedRate stops on a thrown
        // exception).
        heartbeat.scheduleAtFixedRate(guard(this::ping), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        heartbeat.scheduleAtFixedRate(guard(this::pollStats), 1, 1, TimeUnit.SECONDS); // push model/usage on change
        heartbeat.scheduleAtFixedRate(guard(this::pollStatusLines), 1, 1, TimeUnit.SECONDS); // push keyed/footer status
        http.start();
        session.addEventListener(eventListener); // attach only once serving, so a bind failure leaves no dangling
                                                 // listener
        log.info("web ui on http://{}:{}", host, port);
    }

    /** Wraps a periodic task so a thrown error is logged, never propagated (which would cancel its schedule). */
    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.warn("web periodic task failed (will retry next tick): {}", t.toString());
            }
        };
    }

    /**
     * Printable "open on your phone" banner: the reachable URL (LAN IP when bound to {@code 0.0.0.0}) plus a scannable
     * terminal QR. {@code token} is appended as {@code ?t=} when non-null.
     */
    public static List<String> accessBanner(String bindHost, int port, String token) {
        String displayHost = bindHost;
        if ("0.0.0.0".equals(bindHost)) {
            String lan = ai.pijota.util.Net.lanAddress(); // enumerate interfaces once, not twice
            displayHost = lan != null ? lan : DEFAULT_HOST;
        }
        String url = "http://" + displayHost + ":" + port + "/" + (token == null ? "" : "?t=" + token);
        return ai.pijota.util.QrTerminal.scannable("scan to open on your phone:", url);
    }

    @Override
    public void close() {
        ui.cancelPending(); // unblock any tool waiting on a dialog (e.g. a pending /question) so the turn can't hang
        session.removeEventListener(eventListener); // detach from the live session (in-process /remote-control stop)
        heartbeat.shutdownNow();
        if (http != null)
            http.stop(0);
        for (SseClient c : clients)
            c.release();
    }

    /**
     * Token gate for non-loopback binds: when {@link #authToken} is set, a request is allowed only if it carries the
     * token via the {@code pijota_rc} cookie or a {@code ?t=} query param (the latter is how the QR/URL bootstraps —
     * the first GET sets the cookie). Returns true (and sends 403) when refused. No token configured ⇒ never gates.
     */
    private boolean authBlocked(HttpExchange ex) throws IOException {
        if (authToken == null)
            return false; // no token configured — open (loopback --web behaviour)
        if (tokenMatches(cookieToken(ex)))
            return false; // already paired
        if (tokenMatches(queryToken(ex))) {
            // bootstrap from the URL/QR token: pin it as an HttpOnly cookie so later XHRs (no token in URL) still pass
            ex.getResponseHeaders().add("Set-Cookie", "pijota_rc=" + authToken + "; Path=/; HttpOnly; SameSite=Strict");
            return false;
        }
        send(ex, 403, "text/plain", "unauthorized".getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private boolean tokenMatches(String candidate) {
        return candidate != null && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                                        authToken.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryToken(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        if (q == null)
            return null;
        for (String part : q.split("&")) {
            if (part.startsWith("t="))
                return URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String cookieToken(HttpExchange ex) {
        String cookies = ex.getRequestHeaders().getFirst("Cookie");
        if (cookies == null)
            return null;
        for (String c : cookies.split(";")) {
            String t = c.trim();
            if (t.startsWith("pijota_rc="))
                return t.substring("pijota_rc=".length());
        }
        return null;
    }

    // ---- routes ----

    private void serveIndex(HttpExchange ex) throws IOException {
        if (authBlocked(ex))
            return;
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            byte[] html = in.readAllBytes();
            // The single-page app is the whole UI; never let a browser serve a stale copy after an upgrade.
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            send(ex, 200, "text/html; charset=utf-8", html);
        }
    }

    /** Serves the version-pinned vendored UI libraries (marked / highlight.js / DOMPurify) from the classpath. */
    private void serveVendor(HttpExchange ex) throws IOException {
        if (authBlocked(ex))
            return;
        String name = ex.getRequestURI().getPath().substring("/vendor/".length());
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.contains("..")) { // flat whitelist — no path traversal
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/vendor/" + name)) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String type = name.endsWith(".css")
                                            ? "text/css; charset=utf-8"
                                            : name.endsWith(".js")
                                                                            ? "text/javascript; charset=utf-8"
                                                                            : "application/octet-stream";
            ex.getResponseHeaders().set("Cache-Control", "max-age=86400"); // pinned; refetch daily, hard-reload after
                                                                           // update
            send(ex, 200, type, in.readAllBytes());
        }
    }

    /** Long-lived SSE connection: replay history, then stream live events until the browser disconnects. */
    private void serveEvents(HttpExchange ex) throws IOException {
        if (authBlocked(ex))
            return;
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0); // 0 => chunked, open-ended
        SseClient client = new SseClient(ex.getResponseBody());
        clients.add(client);
        try {
            client.write(frame("history", Util.MAPPER.writeValueAsString(history())));
            client.write(frame("statuslines", Util.MAPPER.writeValueAsString(Map.of("lines", lastStatusLines))));
            client.write(frame("stats", Util.MAPPER.writeValueAsString(statsMap())));
            // Replay any dialog still awaiting an answer (e.g. a pending /question) so a tab opened mid-question sees
            // it.
            for (Map<String, Object> dialog : ui.pendingDialogs())
                client.write(frame("dialog", Util.MAPPER.writeValueAsString(dialog)));
            client.await(); // parked until a write fails (disconnect) or the server closes
        } catch (IOException ignored) {
            // client went away before/while replaying history
        } finally {
            clients.remove(client);
            ex.close();
        }
    }

    private void handlePrompt(HttpExchange ex) throws IOException {
        if (authBlocked(ex) || requirePost(ex) || crossOriginBlocked(ex))
            return;
        String text = strField(readBody(ex), "text");
        if (text != null && !text.isBlank()) {
            if (text.stripLeading().startsWith("/"))
                runCommand(text.strip());
            else {
                broadcast("user", Map.of("content", text));
                if (session.isBusy())
                    session.followUp(text); // queue onto the running turn, mirroring the TUI
                else
                    session.prompt(text).exceptionally(t -> {
                        broadcast("agent_error", Map.of("message", ErrorUtil.message(t)));
                        return List.of();
                    });
            }
        }
        send(ex, 204, "text/plain", new byte[0]);
    }

    private void handleCommands(HttpExchange ex) throws IOException {
        var list = new ArrayList<Map<String, Object>>();
        for (SlashCommand c : commands)
            list.add(Map.of("name", "/" + c.name(), "description", nz(c.description())));
        send(ex, 200, "application/json; charset=utf-8", Util.MAPPER.writeValueAsBytes(list));
    }

    /**
     * Runs an extension slash command. The command line is echoed as a custom note (commands aren't part of the LLM
     * conversation), and the handler runs off the request thread since some block. Unknown commands report back instead
     * of being sent to the model.
     */
    private void runCommand(String line) {
        String name = line.split("\\s+", 2)[0].substring(1); // drop leading '/' (matches() is case-insensitive)
        String args = line.length() > name.length() + 1 ? line.substring(name.length() + 1).strip() : "";
        SlashCommand cmd = commands.stream().filter(c -> c.matches(name)).findFirst().orElse(null);
        if (cmd == null) {
            broadcast("status", Map.of("message", "unknown command: /" + name, "alert", true));
            return;
        }
        broadcast("custom", Map.of("type", "command", "content", line));
        CompletableFuture.runAsync(() -> cmd.handler().accept(args)).exceptionally(t -> {
            broadcast("agent_error", Map.of("message", ErrorUtil.message(t)));
            return null;
        });
    }

    private void handleInterrupt(HttpExchange ex) throws IOException {
        if (authBlocked(ex) || requirePost(ex) || crossOriginBlocked(ex))
            return;
        session.abort();
        send(ex, 204, "text/plain", new byte[0]);
    }

    private void handleAnswer(HttpExchange ex) throws IOException {
        if (authBlocked(ex) || requirePost(ex) || crossOriginBlocked(ex))
            return;
        String body = readBody(ex);
        ui.resolve(strField(body, "id"), strField(body, "value"));
        send(ex, 204, "text/plain", new byte[0]);
    }

    // ---- event fan-out ----

    private void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.TurnStart ignored -> broadcast("turn", Map.of("busy", true));
            case AgentEvent.TurnEnd ignored -> broadcast("turn", Map.of("busy", false));
            case AgentEvent.MessageStreamStart s -> broadcast("stream_start", Map.of("type", s.messageType()));
            case AgentEvent.AssistantMessage(Message.Assistant m, var usage) -> {
                // Fires per token with the FULL accumulated content; coalesce intermediate frames so a long answer
                // doesn't serialize+push O(n²) bytes on the streaming thread. The final frame (usage != null) always
                // sends so the browser ends on complete content.
                boolean isFinal = usage != null;
                long now = System.currentTimeMillis();
                if (isFinal || now - lastStreamAt >= STREAM_COALESCE_MS) {
                    lastStreamAt = now;
                    broadcast("assistant", assistant(m, isFinal));
                }
            }
            case AgentEvent.ToolStart(var name, var id, var args) -> broadcast("tool", Map.of("id", nz(id), "name",
                                            nz(name), "args", nz(args.get()), "detail",
                                            nz(ToolLabels.detail(name, args, session.cwd(), PathDisplay.RELATIVE))));
            case AgentEvent.ToolResult(Message.ToolResult r) ->
                broadcast("tool_result", Map.of("id", nz(r.toolCallId()), "name", nz(r.toolName()), "content",
                                                nz(r.content()), "isError", r.isError()));
            case AgentEvent.CustomMessage(Message.Custom c) -> {
                if (!c.isSilent()) // persisted state snapshots (e.g. goal status) aren't shown in the transcript
                    broadcast("custom", Map.of("type", nz(c.customType()), "content", nz(c.content())));
            }
            case AgentEvent.Error(Throwable t) -> broadcast("agent_error", Map.of("message", ErrorUtil.message(t)));
            case AgentEvent.HistoryChanged ignored -> broadcast("history", history()); // re-render after new/load/undo
            default -> {
                // TurnStart/compaction internals/stream deltas have no distinct browser representation here
            }
        }
    }

    /** Serializes the whole conversation so a freshly-connected (or reloaded) browser can render it. */
    private List<Map<String, Object>> history() {
        var out = new ArrayList<Map<String, Object>>();
        for (Message m : session.messages()) {
            switch (m) {
                case Message.User u -> out.add(Map.of("role", "user", "content", nz(u.content())));
                case Message.Assistant a -> {
                    if (!nz(a.content()).isBlank() || !nz(a.thinking()).isBlank() || !a.toolCalls().isEmpty())
                        out.add(assistant(a, true));
                }
                case Message.ToolResult r -> out.add(Map.of("role", "tool", "id", nz(r.toolCallId()), "name",
                                                nz(r.toolName()), "content", nz(r.content()), "isError", r.isError()));
                case Message.Custom c -> {
                    if (!c.isSilent())
                        out.add(Map.of("role", "custom", "type", nz(c.customType()), "content", nz(c.content())));
                }
                default -> {
                    // System/CompactionSummary are internal context, not shown in the chat transcript
                }
            }
        }
        return out;
    }

    private Map<String, Object> assistant(Message.Assistant m, boolean isFinal) {
        var tools = new ArrayList<Map<String, Object>>();
        for (ToolCall tc : m.toolCalls())
            tools.add(Map.of("id", nz(tc.id()), "name", nz(tc.name()), "args", nz(tc.arguments()), "detail",
                                            nz(ToolLabels.detail(tc.name(), tc::arguments, session.cwd(),
                                                                            PathDisplay.RELATIVE))));
        var map = new LinkedHashMap<String, Object>();
        map.put("role", "assistant");
        map.put("content", nz(m.content()));
        map.put("thinking", nz(m.thinking()));
        map.put("toolCalls", tools);
        map.put("final", isFinal);
        return map;
    }

    private void broadcast(String event, Object data) {
        if (clients.isEmpty())
            return;
        byte[] frame;
        try {
            frame = frame(event, Util.MAPPER.writeValueAsString(data)); // build the frame once, not per client
        } catch (Exception e) {
            log.warn("web event serialize failed: {}", e.getMessage());
            return;
        }
        for (SseClient c : clients)
            write(c, frame);
    }

    /** Writes one prebuilt frame to a client, dropping it if the tab is gone. */
    private void write(SseClient c, byte[] frame) {
        try {
            c.write(frame);
        } catch (IOException disconnected) {
            c.release();
            clients.remove(c);
        }
    }

    /** Pushes the aggregated status lines (keyed statuses + status/footer providers) when they change. */
    private void pollStatusLines() {
        if (statusLines == null || clients.isEmpty())
            return;
        List<String> lines = statusLines.get();
        if (lines.equals(lastStatusLines))
            return;
        lastStatusLines = List.copyOf(lines);
        broadcast("statuslines", Map.of("lines", lastStatusLines));
    }

    /** Pushes the current model + usage to the header when it changes (reflects /model switches and accruing cost). */
    private void pollStats() {
        if (llm == null || clients.isEmpty())
            return;
        Map<String, Object> stats = statsMap(); // one builder, shared with the on-connect replay
        if (stats.equals(lastStats))
            return;
        lastStats = stats;
        broadcast("stats", stats);
    }

    private Map<String, Object> statsMap() {
        if (llm == null)
            return Map.of();
        return Map.of("model", nz(llm.provider()) + "/" + nz(llm.model()), "context", session.estimatedContextTokens(),
                                        "cost", llm.totalCost());
    }

    private void ping() {
        for (SseClient c : clients)
            write(c, PING);
    }

    // ---- small HTTP helpers ----

    private static boolean requirePost(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod()))
            return false;
        send(ex, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /**
     * CSRF guard for the state-changing endpoints: a browser tab on any other site can POST to this server, so a
     * malicious page could drive the (shell-executing) agent. Same-origin means the request's {@code Origin} host
     * matches the {@code Host} header the browser actually connected to — true for loopback AND for a LAN/0.0.0.0 bind
     * reached from another device (e.g. a phone), since the served page and its POSTs share that host. A genuine
     * cross-site request carries the attacking site's Origin, which won't match our Host. Absent Origin = a non-browser
     * client (curl/CLI) with no CSRF vector. Returns true (and sends 403) when the request was refused.
     */
    private boolean crossOriginBlocked(HttpExchange ex) throws IOException {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank())
            return false;
        String originHost = null;
        try {
            originHost = URI.create(origin).getHost();
        } catch (RuntimeException ignored) {}
        String hostHeader = ex.getRequestHeaders().getFirst("Host");
        String connectedHost = hostHeader == null ? null : hostHeader.replaceFirst(":\\d+$", "");
        if (originHost != null && (originHost.equalsIgnoreCase(connectedHost) || isLoopbackHost(originHost)))
            return false;
        send(ex, 403, "text/plain", "cross-origin request refused".getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private static boolean isLoopbackHost(String h) {
        return h.equals("127.0.0.1") || h.equalsIgnoreCase("localhost") || h.equals("::1");
    }

    private static final int MAX_BODY_BYTES = 2_000_000; // prompts are tiny; cap to avoid unbounded-body OOM
    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        }
    }

    /** Pulls one string field out of a small JSON object body; null if absent. Avoids a DTO per endpoint. */
    private static String strField(String json, String field) {
        try {
            var node = Util.MAPPER.readTree(json).get(field);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0)
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
    }

    /** Builds an SSE frame; data may not contain a bare newline, so each line gets its own {@code data:} field. */
    private static byte[] frame(String event, String json) {
        var sb = new StringBuilder("event: ").append(event).append('\n');
        for (String line : json.split("\n", -1))
            sb.append("data: ").append(line).append('\n');
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

    /** One browser's SSE pipe. Writes are serialized; a failed write means the tab is gone. */
    private static final class SseClient {
        private final OutputStream out;
        private final CountDownLatch closed = new CountDownLatch(1);

        SseClient(OutputStream out) {
            this.out = out;
        }

        synchronized void write(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();
        }

        void await() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            closed.countDown();
        }
    }
}
