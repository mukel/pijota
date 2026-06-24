package ai.pijota.web;

import ai.pijota.agent.TestSession;
import ai.pijota.messages.Message;
import ai.pijota.testkit.Await;
import ai.pijota.testkit.FauxLlm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the real {@code --web} server on a loopback port (no browser, no network) and drives it over HTTP: the static
 * SPA route, the catch-all 404, POST-only guarding, the SSE event stream, and a full prompt → agent → broadcast
 * round-trip against a faux LLM. Uses {@link TestSession} for a real session.
 */
class WebServerTest {

    @TempDir
    Path tempDir;

    private interface ServerTest {
        void run(TestSession session, HttpClient client, String base) throws Exception;
    }

    private void withServer(ServerTest body) throws Exception {
        var ts = TestSession.in(tempDir).build();
        int port = freePort();
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of())) {
            server.start();
            body.run(ts, HttpClient.newHttpClient(), "http://127.0.0.1:" + port);
        }
    }

    @Test
    void parseAddressDefaultsToLoopbackAndDefaultPort() {
        for (String bare : new String[] {null, "", "true"}) {
            var a = WebServer.parseAddress(bare);
            assertEquals("127.0.0.1", a.host());
            assertEquals(WebServer.DEFAULT_PORT, a.port());
            assertTrue(a.isLoopback());
        }
    }

    @Test
    void parseAddressBarePortKeepsLoopback() {
        var a = WebServer.parseAddress("9000");
        assertEquals("127.0.0.1", a.host());
        assertEquals(9000, a.port());
        assertTrue(a.isLoopback());
    }

    @Test
    void parseAddressHostPortBindsTheGivenHost() {
        var a = WebServer.parseAddress("0.0.0.0:9000");
        assertEquals("0.0.0.0", a.host());
        assertEquals(9000, a.port());
        assertFalse(a.isLoopback(), "0.0.0.0 is not loopback → triggers the exposure warning");
    }

    @Test
    void parseAddressBareHostUsesDefaultPort() {
        var a = WebServer.parseAddress("0.0.0.0");
        assertEquals("0.0.0.0", a.host());
        assertEquals(WebServer.DEFAULT_PORT, a.port());
    }

    @Test
    void parseAddressLocalhostIsLoopback() {
        assertTrue(WebServer.parseAddress("localhost:9000").isLoopback());
    }

    @Test
    void parseAddressInvalidPortFallsBackToDefault() {
        var a = WebServer.parseAddress("0.0.0.0:notaport");
        assertEquals("0.0.0.0", a.host());
        assertEquals(WebServer.DEFAULT_PORT, a.port());
    }

    @Test
    void servesSinglePageAppAtRoot() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().toLowerCase().contains("<html") || resp.body().toLowerCase().contains("<!doctype"),
                                            "root should serve the SPA html");
            // never let a browser cache a stale SPA after an upgrade
            assertEquals("no-store", resp.headers().firstValue("Cache-Control").orElse(""));
        });
    }

    @Test
    void servesVendoredLibrariesLocally() throws Exception {
        withServer((ts, client, base) -> {
            var js = client.send(HttpRequest.newBuilder(URI.create(base + "/vendor/marked.min.js")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, js.statusCode());
            assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("javascript"));
            assertFalse(js.body().isBlank());

            var css = client.send(HttpRequest.newBuilder(URI.create(base + "/vendor/github-dark.min.css")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, css.statusCode());
            assertTrue(css.headers().firstValue("Content-Type").orElse("").contains("css"));

            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/vendor/nope.js")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
        });
    }

    @Test
    void spaReferencesLocalVendorNotCdn() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertTrue(resp.body().contains("/vendor/marked.min.js"), "SPA should load libs locally");
            assertFalse(resp.body().contains("cdn.jsdelivr"), "UI must not depend on a CDN");
        });
    }

    @Test
    void unknownPathReturns404() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/does-not-exist")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(404, resp.statusCode());
        });
    }

    @Test
    void promptEndpointRejectsNonPost() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/prompt")).GET().build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(405, resp.statusCode());
        });
    }

    @Test
    void tokenGateRejectsWithoutTokenAndAcceptsWithIt() throws Exception {
        var ts = TestSession.in(tempDir).build();
        int port = freePort();
        String token = "secret-pairing-xyz";
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of(), token)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;

            var denied = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), "no token → refused");

            var ok = client.send(HttpRequest.newBuilder(URI.create(base + "/?t=" + token)).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), "valid ?t= token → served");
            assertTrue(ok.headers().firstValue("Set-Cookie").orElse("").contains("pijota_rc="),
                                            "pairs the browser by setting the cookie");
        }
    }

    @Test
    void accessBannerContainsUrlAndToken() {
        String banner = String.join("\n", WebServer.accessBanner("127.0.0.1", 8420, "tok123"));
        assertTrue(banner.contains("127.0.0.1:8420"), banner);
        assertTrue(banner.contains("t=tok123"), banner);
    }

    @Test
    void eventsStreamReplaysHistoryFrame() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
            // serveEvents writes history/statuslines/stats frames immediately, then parks; read until we see history.
            String frames = readUntil(resp.body(), "event: history", Duration.ofSeconds(5));
            assertTrue(frames.contains("event: history"), frames);
        });
    }

    @Test
    void historyFrameOmitsSilentCustomMessages() throws Exception {
        withServer((ts, client, base) -> {
            // a goal-style state snapshot (silent) and an ordinary custom note
            ts.session().agentLoop().addCustomMessage(new Message.Custom("goal", "active · secret goal snapshot",
                                            Map.of("silent", true)));
            ts.session().agentLoop().addCustomMessage(new Message.Custom("note", "a visible note", Map.of()));

            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
            String frames = readUntil(resp.body(), "event: history", Duration.ofSeconds(5));

            assertTrue(frames.contains("a visible note"), frames);
            assertFalse(frames.contains("secret goal snapshot"),
                                            "silent state snapshots must not reach the browser: " + frames);
        });
    }

    @Test
    void promptDrivesTheAgentAndStoresTheAnswer() throws Exception {
        withServer((ts, client, base) -> {
            ts.llm().enqueue(FauxLlm.Response.contentWithUsage("hi from the agent"));

            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hello server\"}"))
                                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, resp.statusCode());

            // the handler fires the turn in the background; the faux LLM answers immediately
            Await.until(() -> "hi from the agent".equals(ts.session().lastAssistantContent()), Duration.ofSeconds(5),
                                            "agent never produced the answer");
            assertEquals(1, ts.session().userMessages().size());
            assertEquals("hello server", ts.session().userMessages().getFirst().content());
        });
    }

    /** Reads from a (possibly open-ended) stream on a daemon thread until the marker appears or the timeout elapses. */
    private static String readUntil(InputStream in, String marker, Duration timeout) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            var sb = new StringBuilder();
            var buf = new byte[2048];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (sb.indexOf(marker) >= 0)
                        break;
                }
            } catch (IOException ignored) {
                // stream closed (server shutdown) — return what we have
            }
            return sb.toString();
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return ""; // marker never arrived; caller asserts
        }
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
