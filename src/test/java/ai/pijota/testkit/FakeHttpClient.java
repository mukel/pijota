package ai.pijota.testkit;

import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * A canned {@link HttpClient} for unit tests: every {@code send}/{@code sendAsync} returns the configured status and
 * body without touching the network, so a test can drive any component that takes an injected client (search/fetch
 * tools, the model refresher) against a fixed response and assert how it parses it.
 *
 * <p>
 * The body must match the {@code BodyHandler} the code under test uses: {@link #ok(String)} for
 * {@code BodyHandlers.ofString()}, {@link #ok(byte[])} for {@code ofByteArray()}. The handler itself is ignored — the
 * stored body is returned verbatim. The last request sent is exposed via {@link #lastRequest()} so tests can assert URL
 * / headers without a network round-trip.
 *
 * <pre>{@code
 * var tool = new WebsearchTool(FakeHttpClient.ok(ddgHtml));
 * var result = run(tool, Map.of("query", "java"));
 * }</pre>
 */
public final class FakeHttpClient extends HttpClient {
    private record Resp(int status, Object body) {
    } // body: String (ofString) or byte[] (ofByteArray)

    private final List<Resp> responses; // returned in order; the last one repeats for further calls
    private int callIndex = 0;
    private volatile HttpRequest lastRequest;

    private FakeHttpClient(List<Resp> responses) {
        this.responses = responses;
    }

    private FakeHttpClient(int status, Object body) {
        this(List.of(new Resp(status, body)));
    }

    public static FakeHttpClient ok(String body) {
        return new FakeHttpClient(200, body);
    }

    public static FakeHttpClient ok(byte[] body) {
        return new FakeHttpClient(200, body);
    }

    /** For code that uses {@code BodyHandlers.ofInputStream()} (e.g. SSE streaming). */
    public static FakeHttpClient ok(InputStream body) {
        return new FakeHttpClient(200, body);
    }

    public static FakeHttpClient status(int code, String body) {
        return new FakeHttpClient(code, body);
    }

    public static FakeHttpClient status(int code, byte[] body) {
        return new FakeHttpClient(code, body);
    }

    /** Returns each body (HTTP 200) in order across successive calls; the last repeats — for poll/retry flows. */
    public static FakeHttpClient sequence(String... bodies) {
        var list = new ArrayList<Resp>();
        for (String b : bodies)
            list.add(new Resp(200, b));
        return new FakeHttpClient(list);
    }

    /** The most recent request handed to {@code send}/{@code sendAsync}, for asserting the URL/headers built. */
    public HttpRequest lastRequest() {
        return lastRequest;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        lastRequest = request;
        Resp r = responses.get(Math.min(callIndex++, responses.size() - 1));
        return new FakeResponse<>(request, r.status(), (T) r.body());
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return CompletableFuture.completedFuture(send(request, handler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                    HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, handler);
    }

    // --- configuration accessors: unused by the code under test, so they return harmless defaults ---
    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return null;
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        throw new UnsupportedOperationException("FakeHttpClient does not support WebSocket");
    }

    /** Minimal {@link HttpResponse} carrying the canned status + body. */
    private record FakeResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
