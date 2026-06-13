package ai.pijota.extensions.mcp;

import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JSON-RPC 2.0 client for one MCP server over an {@link McpTransport}. Owns a single reader thread that demultiplexes
 * incoming lines: responses (carrying an {@code id}) complete the matching pending request; server→client requests get
 * a {@code method not found} reply (we implement no server-callable methods yet); notifications are ignored.
 *
 * <p>
 * Design notes that matter for correctness:
 * <ul>
 * <li>Every request future has a timeout, so a silent server can never hang an agent turn.</li>
 * <li>If the transport dies, all in-flight futures are failed — a leaked pending future would block the loop
 * forever.</li>
 * <li>Futures are completed on a caller-supplied {@link Executor}, never the reader thread, so downstream tool/agent
 * continuations cannot stall further reads.</li>
 * </ul>
 */
final class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final String serverName;
    private final McpTransport transport;
    private final Executor completionExecutor;
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    McpClient(String serverName, McpTransport transport, Executor completionExecutor) {
        this.serverName = serverName;
        this.transport = transport;
        this.completionExecutor = completionExecutor;
    }

    /** Starts the reader, performs the {@code initialize} handshake, and sends {@code notifications/initialized}. */
    void connect(Duration timeout) throws IOException {
        Thread.ofVirtual().name("mcp-reader-" + serverName).start(this::readLoop);
        var params = Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of(), "clientInfo",
                                        Map.of("name", "pijota", "version", "0.4.0"));
        await(request("initialize", params), timeout); // confirm the server initializes before using it
        notify("notifications/initialized", Map.of());
    }

    /** Lists every tool the server exposes, following {@code nextCursor} pagination. */
    List<McpToolDescriptor> listTools(Duration timeout) throws IOException {
        var out = new ArrayList<McpToolDescriptor>();
        String cursor = null;
        do {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            JsonNode result = await(request("tools/list", params), timeout);
            JsonNode tools = result.get("tools");
            if (tools != null && tools.isArray()) {
                for (JsonNode t : tools) {
                    String name = text(t, "name");
                    if (name == null)
                        continue;
                    out.add(new McpToolDescriptor(name, text(t, "description"), asMap(t.get("inputSchema"))));
                }
            }
            JsonNode next = result.get("nextCursor");
            cursor = next != null && next.isTextual() ? next.asText() : null;
        } while (cursor != null);
        return out;
    }

    /**
     * Invokes {@code tools/call} and yields the result as an {@link McpCallResult} (content flattened to text). The
     * future fails on a protocol error, timeout, or the server dying; a tool-level failure surfaces as a normal result
     * with {@link McpCallResult#isError()} set.
     */
    CompletableFuture<McpCallResult> callTool(String toolName, Map<String, Object> arguments, Duration timeout) {
        var params = new LinkedHashMap<String, Object>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return request("tools/call", params).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                                        .thenApply(McpClient::toCallResult);
    }

    /** Flattens an MCP {@code tools/call} result's {@code content[]} into text; non-text parts are summarized. */
    private static McpCallResult toCallResult(JsonNode result) {
        if (result == null)
            return new McpCallResult("", false);
        var sb = new StringBuilder();
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                if (sb.length() > 0)
                    sb.append('\n');
                String type = part.path("type").asText("");
                sb.append("text".equals(type)
                                                ? part.path("text").asText("")
                                                : "[" + (type.isBlank() ? "non-text content" : type) + " omitted]");
            }
        }
        return new McpCallResult(sb.toString(), result.path("isError").asBoolean(false));
    }

    boolean alive() {
        return !closed && transport.alive();
    }

    void close() {
        closed = true;
        transport.close();
        failAll(new IOException("MCP server " + serverName + " closed"));
    }

    // --- internals ---

    private CompletableFuture<JsonNode> request(String method, Object params) {
        long id = nextId.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        future.whenComplete((r, ex) -> pending.remove(id));
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? Map.of() : params);
        try {
            transport.send(Util.MAPPER.writeValueAsString(message));
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    private void notify(String method, Object params) throws IOException {
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params == null ? Map.of() : params);
        transport.send(Util.MAPPER.writeValueAsString(message));
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = transport.receive()) != null) {
                if (line.isBlank())
                    continue;
                dispatch(Util.MAPPER.readTree(line));
            }
        } catch (IOException e) {
            if (!closed)
                log.debug("MCP {} read loop ended: {}", serverName, e.getMessage());
        } finally {
            failAll(new IOException("MCP server " + serverName + " disconnected"));
        }
    }

    private void dispatch(JsonNode node) {
        boolean hasId = node.hasNonNull("id");
        boolean isResponse = node.has("result") || node.has("error");
        if (hasId && isResponse) {
            CompletableFuture<JsonNode> future = pending.remove(node.get("id").asLong());
            if (future == null)
                return;
            if (node.has("error")) {
                String msg = text(node.get("error"), "message");
                completionExecutor.execute(() -> future.completeExceptionally(
                                                new McpException(msg != null ? msg : node.get("error").toString())));
            } else {
                JsonNode result = node.get("result");
                completionExecutor.execute(() -> future.complete(result));
            }
        } else if (node.has("method") && hasId) {
            // Server→client request: we expose no callable methods yet — reply so the server doesn't stall waiting.
            replyMethodNotFound(node.get("id"));
        }
        // else: a notification (method, no id) — ignored for now.
    }

    private void replyMethodNotFound(JsonNode id) {
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", idValue(id));
        message.put("error", Map.of("code", -32601, "message", "Method not found"));
        try {
            transport.send(Util.MAPPER.writeValueAsString(message));
        } catch (IOException ignored) {}
    }

    private void failAll(Throwable cause) {
        for (var entry : List.copyOf(pending.entrySet())) {
            CompletableFuture<JsonNode> future = pending.remove(entry.getKey());
            if (future != null)
                completionExecutor.execute(() -> future.completeExceptionally(cause));
        }
    }

    private static JsonNode await(CompletableFuture<JsonNode> future, Duration timeout) throws IOException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("MCP request timed out");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static Object idValue(JsonNode id) {
        return id.isNumber() ? id.asLong() : id.asText();
    }

    private static String text(JsonNode node, String field) {
        if (node == null)
            return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode node) {
        if (node == null || !node.isObject())
            return null;
        return Util.MAPPER.convertValue(node, Map.class);
    }

    static final class McpException extends RuntimeException {
        McpException(String message) {
            super(message);
        }
    }
}
