package ai.pijota.acp;

import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Newline-delimited JSON-RPC 2.0 peer over a byte stream (stdio) — the transport for the ACP frontend. Unlike
 * {@link ai.pijota.modes.RpcMode}, which only answers requests, this is fully duplex: besides serving incoming requests
 * it can issue its own requests to the peer (ACP {@code session/request_permission}, {@code fs/*}, {@code terminal/*})
 * and correlate their replies. One JSON message per line; the three JSON-RPC frame shapes are distinguished as:
 * <ul>
 * <li>request — has {@code method} and {@code id} → routed to a request handler, which produces a result</li>
 * <li>notification — has {@code method}, no {@code id} → routed to a notification handler (fire-and-forget)</li>
 * <li>response — has {@code id}, no {@code method} → completes the matching outbound {@link #request}</li>
 * </ul>
 * The owning frontend may write from several threads (request handlers on the read loop; {@code session/update}
 * notifications from the agent thread), so {@link #writeMessage} is serialized.
 */
public final class AcpPeer {

    private final BufferedReader reader;
    private final Writer writer;
    private final Object writeLock = new Object();

    // Optional protocol tracing for debugging a client (set PIJOTA_ACP_WIRELOG=/path). Incoming lines are prefixed
    // "<< ", outgoing ">> ". Null (off) unless the env var is set, so it never touches the normal path. Opened once —
    // not reopened per frame — since streaming emits one frame per token.
    private final Writer wireLog = openWireLog();

    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Function<JsonNode, CompletableFuture<JsonNode>>> requestHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<JsonNode>> notificationHandlers = new ConcurrentHashMap<>();
    private volatile Consumer<String> onResponded = m -> {};

    public AcpPeer(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    /** Registers a handler for an incoming request method; its future resolves to the JSON-RPC {@code result}. */
    public AcpPeer onRequest(String method, Function<JsonNode, CompletableFuture<JsonNode>> handler) {
        requestHandlers.put(method, handler);
        return this;
    }

    /** Registers a handler for an incoming notification; no reply is sent. */
    public AcpPeer onNotification(String method, Consumer<JsonNode> handler) {
        notificationHandlers.put(method, handler);
        return this;
    }

    /**
     * Registers a hook run with the request's method name immediately after that request's result is written — the seam
     * for a follow-up notification that must be strictly ordered after a response (e.g.
     * {@code available_commands_update} after {@code session/new}). For a synchronously-resolved handler it runs on the
     * read thread, right after the write.
     */
    public AcpPeer onResponded(Consumer<String> handler) {
        this.onResponded = handler != null ? handler : m -> {};
        return this;
    }

    /** Reads and dispatches frames until EOF (the peer closed stdin). Blocks the calling thread. */
    public void run() throws IOException {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;
                trace("<< ", line);
                JsonNode node;
                try {
                    node = Util.MAPPER.readTree(line);
                } catch (Exception parseError) {
                    // Can't correlate a malformed line to an id, so answer with a null-id parse error per JSON-RPC.
                    writeError(NullNode.getInstance(), -32700, "Parse error");
                    continue;
                }
                dispatch(node);
            }
        } finally {
            closeWireLog();
        }
    }

    private void dispatch(JsonNode node) {
        JsonNode idNode = node.get("id");
        String method = node.hasNonNull("method") ? node.get("method").asText() : null;

        if (method != null) {
            // params is only meaningful for requests/notifications; responses (id, no method) route below by id.
            JsonNode params = node.hasNonNull("params") ? node.get("params") : Util.MAPPER.createObjectNode();
            if (idNode != null && !idNode.isNull())
                handleIncomingRequest(idNode, method, params);
            else
                handleIncomingNotification(method, params);
            return;
        }
        // No method → a response to one of our outbound requests.
        Long id = asRequestId(idNode);
        if (id == null)
            return;
        CompletableFuture<JsonNode> waiting = pending.remove(id);
        if (waiting == null)
            return;
        if (node.has("error"))
            waiting.completeExceptionally(new AcpRemoteException(node.get("error")));
        else
            waiting.complete(node.path("result"));
    }

    private void handleIncomingRequest(JsonNode idNode, String method, JsonNode params) {
        Function<JsonNode, CompletableFuture<JsonNode>> handler = requestHandlers.get(method);
        if (handler == null) {
            writeError(idNode, -32601, "Method not found: " + method);
            return;
        }
        CompletableFuture<JsonNode> result;
        try {
            result = handler.apply(params);
        } catch (Exception e) {
            writeError(idNode, -32603, rootMessage(e));
            return;
        }
        result.whenComplete((value, error) -> {
            if (error != null) {
                writeError(idNode, -32603, rootMessage(error));
            } else {
                writeResult(idNode, value == null ? NullNode.getInstance() : value);
                onResponded.accept(method); // strictly after the response is on the wire
            }
        });
    }

    private void handleIncomingNotification(String method, JsonNode params) {
        Consumer<JsonNode> handler = notificationHandlers.get(method);
        if (handler == null)
            return;
        try {
            handler.accept(params);
        } catch (Exception ignored) {
            // Notifications have no reply channel; a handler fault must not tear down the read loop.
        }
    }

    /** Issues an outbound request and completes when the peer replies (or exceptionally on a JSON-RPC error). */
    public CompletableFuture<JsonNode> request(String method, JsonNode params) {
        long id = nextId.getAndIncrement();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        ObjectNode req = Util.MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null)
            req.set("params", params);
        writeMessage(req);
        return future;
    }

    /** Sends an outbound notification (no reply expected). */
    public void notify(String method, JsonNode params) {
        ObjectNode msg = Util.MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null)
            msg.set("params", params);
        writeMessage(msg);
    }

    private void writeResult(JsonNode idNode, JsonNode result) {
        ObjectNode msg = Util.MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", idNode);
        msg.set("result", result);
        writeMessage(msg);
    }

    private void writeError(JsonNode idNode, int code, String message) {
        ObjectNode msg = Util.MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", idNode == null ? NullNode.getInstance() : idNode);
        ObjectNode err = msg.putObject("error");
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        writeMessage(msg);
    }

    private void writeMessage(ObjectNode msg) {
        try {
            String line = Util.MAPPER.writeValueAsString(msg);
            trace(">> ", line);
            synchronized (writeLock) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            // stdout is gone (peer closed); the read loop will hit EOF and unwind. Nothing to recover here.
        }
    }

    private static Writer openWireLog() {
        String p = System.getenv("PIJOTA_ACP_WIRELOG");
        if (p == null || p.isBlank())
            return null;
        try {
            return Files.newBufferedWriter(Path.of(p), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND);
        } catch (IOException e) {
            return null; // tracing is best-effort — never block startup on it
        }
    }

    private void trace(String dir, String line) {
        if (wireLog == null)
            return;
        try {
            synchronized (writeLock) {
                wireLog.write(dir);
                wireLog.write(line);
                wireLog.write('\n');
                wireLog.flush();
            }
        } catch (IOException ignored) {
            // tracing is best-effort; never let it disrupt the protocol
        }
    }

    private void closeWireLog() {
        if (wireLog == null)
            return;
        try {
            wireLog.close();
        } catch (IOException ignored) {}
    }

    private static Long asRequestId(JsonNode idNode) {
        if (idNode == null || idNode.isNull())
            return null;
        if (idNode.canConvertToLong())
            return idNode.asLong();
        try {
            return Long.parseLong(idNode.asText());
        } catch (NumberFormatException e) {
            return null; // A string id we never issued — no pending slot to match anyway.
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c)
            c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    /** Wraps a JSON-RPC error object received from the peer, so an outbound {@link #request} fails with its detail. */
    public static final class AcpRemoteException extends RuntimeException {
        public AcpRemoteException(JsonNode error) {
            super(error == null ? "unknown error" : error.toString());
        }
    }
}
