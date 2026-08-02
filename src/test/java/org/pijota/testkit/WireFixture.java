package org.pijota.testkit;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback HTTP server that replays a recorded provider response, so the real request-building and response-parsing
 * code runs with no network and no API key.
 *
 * <p>
 * This is the layer between the shape tests (pijota types to pijota types, which never see a provider's bytes) and
 * {@code make native-smoke} (real keys, not runnable in CI). It catches what those miss: a field parsed into the wrong
 * place, usage or cache tokens dropped, a tool call mis-assembled from streamed fragments, an error body swallowed.
 *
 * <p>
 * It records what was sent as well as what comes back, because half the wire contract is the request: a
 * provider-specific header, the shape of a tool schema, whether thinking was actually enabled.
 */
public final class WireFixture implements AutoCloseable {

    /** One captured request: what pijota actually put on the wire. */
    public record Captured(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            // HttpServer's header map is case-insensitive on lookup, but be explicit for readability at call sites.
            var values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private record Response(int status, String contentType, String body) {
    }

    private final HttpServer server;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private volatile Response response = new Response(200, "text/event-stream", "");

    private WireFixture(HttpServer server) {
        this.server = server;
    }

    /** Starts a fixture on an ephemeral loopback port. */
    public static WireFixture start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var fixture = new WireFixture(http);
        http.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            fixture.captured.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                                            Map.copyOf(exchange.getRequestHeaders()), body));
            Response r = fixture.response;
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", r.contentType());
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http.start();
        return fixture;
    }

    /** Replays {@code body} as a Server-Sent Events stream. */
    public WireFixture replaySse(String body) {
        response = new Response(200, "text/event-stream", body);
        return this;
    }

    /** Replays a plain JSON body (the non-streaming shape some endpoints use). */
    public WireFixture replayJson(String body) {
        response = new Response(200, "application/json", body);
        return this;
    }

    /** Replays an error status with a provider-shaped error body, to exercise the failure path. */
    public WireFixture replayError(int status, String body) {
        response = new Response(status, "application/json", body);
        return this;
    }

    /** The base URL to hand to {@code LlmService}'s customBaseUrl. */
    public String baseUrl() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    public List<Captured> requests() {
        return List.copyOf(captured);
    }

    /** The request pijota sent, for asserting on the half of the contract that leaves the machine. */
    public Captured lastRequest() {
        var all = requests();
        if (all.isEmpty())
            throw new AssertionError("no request reached the fixture");
        return all.get(all.size() - 1);
    }

    /** Frames already-serialized payloads as {@code data: …} events, the OpenAI-compatible shape. */
    public static String sse(String... dataLines) {
        var sb = new StringBuilder();
        for (String line : dataLines)
            sb.append("data: ").append(line).append("\n\n");
        return sb.toString();
    }

    /** Frames name/data pairs as {@code event: … / data: …}, the shape Anthropic uses. */
    public static String sseNamed(String... namedPairs) {
        if (namedPairs.length % 2 != 0)
            throw new IllegalArgumentException("expected name/data pairs");
        var sb = new StringBuilder();
        for (int i = 0; i < namedPairs.length; i += 2)
            sb.append("event: ").append(namedPairs[i]).append("\ndata: ").append(namedPairs[i + 1]).append("\n\n");
        return sb.toString();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
