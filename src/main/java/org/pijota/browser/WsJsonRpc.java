package org.pijota.browser;

import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A minimal JSON-RPC-over-WebSocket transport shared by the browser backends (both CDP and WebDriver BiDi speak it).
 * Requests are correlated by an incrementing id; unsolicited (id-less) event frames go to the {@link #onEvent} sink so
 * the backend can react to lifecycle events rather than poll. The owning backend serializes calls, so this stays
 * lock-free.
 */
final class WsJsonRpc {
    private final HttpClient http;
    private final long connectTimeoutMs;
    private final long callTimeoutMs;

    private final AtomicInteger nextId = new AtomicInteger();
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile WebSocket ws;
    private volatile boolean closed; // tripped by close(); makes an in-flight call() bail out instead of waiting
    private volatile Runnable onDrop = () -> {}; // backend marks itself not-ready when the socket drops or stalls
    private volatile Consumer<JsonNode> eventSink = e -> {}; // server-initiated event frames (no id) → backend

    WsJsonRpc(HttpClient http, long connectTimeoutMs, long callTimeoutMs) {
        this.http = http;
        this.connectTimeoutMs = connectTimeoutMs;
        this.callTimeoutMs = callTimeoutMs;
    }

    /** Called when the socket drops or a call times out, so the backend can reconnect on its next use. */
    void onDrop(Runnable handler) {
        this.onDrop = handler != null ? handler : () -> {};
    }

    /** Registers a sink for server-initiated event frames ({@code {method, params}} with no {@code id}). */
    void onEvent(Consumer<JsonNode> sink) {
        this.eventSink = sink != null ? sink : e -> {};
    }

    void connect(String wsUrl) throws Exception {
        closed = false;
        ws = http.newWebSocketBuilder().buildAsync(URI.create(wsUrl), new Listener()).get(connectTimeoutMs,
                                        TimeUnit.MILLISECONDS);
    }

    boolean isConnected() {
        return ws != null && !closed;
    }

    /** Sends a request and returns its {@code result} node, throwing on error/timeout/close. */
    JsonNode call(String method, ObjectNode params) throws Exception {
        WebSocket sock = ws;
        if (sock == null)
            throw new IllegalStateException("transport not connected");
        int id = nextId.incrementAndGet();
        var fut = awaitId(id);
        try {
            ObjectNode req = Util.MAPPER.createObjectNode();
            req.put("id", id);
            req.put("method", method);
            if (params != null)
                req.set("params", params);
            sock.sendText(Util.MAPPER.writeValueAsString(req), true);
            // Poll so close() (which fails pending) aborts within ~200ms instead of the full timeout, and so a
            // genuinely unresponsive socket forces a reconnect rather than cascading stalls.
            long deadline = System.nanoTime() + callTimeoutMs * 1_000_000L;
            while (true) {
                if (closed)
                    throw new IllegalStateException("transport closed");
                try {
                    return fut.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    if (System.nanoTime() >= deadline) {
                        onDrop.run(); // unresponsive socket → reconnect on the next call
                        throw te;
                    }
                }
            }
        } finally {
            pending.remove(id);
        }
    }

    /** Registers a pending request slot (test seam for the JSON-RPC routing). */
    CompletableFuture<JsonNode> awaitId(int id) {
        var fut = new CompletableFuture<JsonNode>();
        pending.put(id, fut);
        return fut;
    }

    /** Routes a reply frame to its waiting caller; an event frame (no {@code id}) goes to the event sink. */
    void handleMessage(String json) {
        try {
            JsonNode n = Util.MAPPER.readTree(json);
            if (!n.hasNonNull("id")) {
                if (n.hasNonNull("method"))
                    eventSink.accept(n); // CDP/BiDi event → backend reacts (await load, etc.) instead of polling
                return;
            }
            CompletableFuture<JsonNode> fut = pending.remove(n.get("id").asInt());
            if (fut == null)
                return;
            if (n.has("error"))
                fut.completeExceptionally(new IllegalStateException("RPC error: " + n.get("error").toString()));
            else
                fut.complete(n.path("result"));
        } catch (Exception ignored) {
            // malformed frame → drop it; the caller times out and reconnects
        }
    }

    void close() {
        closed = true;
        WebSocket sock = ws;
        ws = null;
        if (sock != null)
            try {
                sock.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {}
        failPending(new IllegalStateException("transport closed"));
    }

    /** Completes-and-removes every in-flight request atomically (so a request registered concurrently can't hang). */
    private void failPending(Throwable t) {
        for (Integer id : pending.keySet()) {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null)
                f.completeExceptionally(t);
        }
    }

    private void drop(WebSocket sock, Throwable t) {
        if (sock == ws) // ignore stale events from a socket we already replaced on reconnect
            onDrop.run();
        failPending(t);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                handleMessage(msg);
            }
            socket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            drop(socket, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            drop(socket, new IllegalStateException("socket closed")); // crash / closed tab → reconnect next call
            return null;
        }
    }
}
