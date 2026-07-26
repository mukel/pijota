package ai.pijota.web;

import ai.pijota.agent.TestSession;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.testkit.Await;
import ai.pijota.testkit.FauxLlm;
import ai.pijota.tools.Tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
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
import java.util.concurrent.TimeoutException;

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
    void rejectsBackslashVendorTraversal() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/vendor/foo%5Cbar.js")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(404, resp.statusCode());
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
    void pairingServesThePageDirectlyAndPinsTheCookie() throws Exception {
        var ts = TestSession.in(tempDir).build();
        int port = freePort();
        String token = "secret-pairing-xyz";
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of(), token)) {
            server.start();
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            String base = "http://127.0.0.1:" + port;

            // the QR opens /?t=<token>: page is served directly (no redirect — robust across mobile browsers) and the
            // cookie is pinned. (The SPA strips ?t= from the address bar client-side via history.replaceState.)
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/?t=" + token)).build(),
                                            HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode(), "valid ?t= → authenticated page, served directly");
            assertFalse(resp.body().isBlank(), "served the SPA");
            assertTrue(resp.body().contains("history.replaceState"), "SPA strips the token from the URL client-side");
            // The cookie MUST be persistent (Max-Age): the SPA strips ?t= from the URL, so a session-only cookie would
            // lock the user out after a browser restart with no way back in. Lax so it rides reloads/navigations.
            String cookie = resp.headers().firstValue("Set-Cookie").orElse("");
            assertTrue(cookie.contains("Max-Age="), "pairing cookie must persist across browser restarts: " + cookie);
            assertTrue(cookie.contains("SameSite=Lax"), "Lax so it survives top-level navigations/reloads: " + cookie);
        }
    }

    @Test
    void noTokenServerServesQueryUrlsWithoutRedirect() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/?x=1")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), "no token mode → no pairing redirect, just serve the SPA");
        });
    }

    @Test
    void commandsEndpointIsTokenGated() throws Exception {
        var ts = TestSession.in(tempDir).build();
        int port = freePort();
        String token = "secret-pairing-xyz";
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of(), token)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;

            var denied = client.send(HttpRequest.newBuilder(URI.create(base + "/commands")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), "GET /commands without a token must not leak the command list");

            var ok = client.send(HttpRequest.newBuilder(URI.create(base + "/commands"))
                                            .header("Cookie", "pijota_rc=" + token).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), "with the cookie → command list");
        }
    }

    @Test
    void newPairingTokenIsUrlSafeUniqueAndHighEntropy() {
        String a = WebServer.newPairingToken();
        String b = WebServer.newPairingToken();
        assertNotEquals(a, b, "tokens must be unguessably unique");
        assertTrue(a.matches("[A-Za-z0-9_-]{30,}"), "URL-safe base64, 24 bytes ≈ 32 chars: " + a);
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
    void interruptAndAnswerEndpointsRejectNonPost() throws Exception {
        withServer((ts, client, base) -> {
            for (String path : new String[] {"/interrupt", "/answer"}) {
                var resp = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                                                HttpResponse.BodyHandlers.ofString());
                assertEquals(405, resp.statusCode(), path + " should be POST-only");
            }
        });
    }

    @Test
    void interruptPostedWhenIdleIsAcceptedAsANoOp() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/interrupt"))
                                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(204, resp.statusCode(), "interrupt is accepted (No Content) even with no turn running");
        });
    }

    @Test
    void commandsEndpointListsRegisteredCommandsWhenUnauthenticated() throws Exception {
        // a no-token server (withServer) doesn't gate routes, so /commands returns the list
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/commands")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("json"), "commands as JSON");
        });
    }

    @Test
    void crossOriginPostIsBlocked() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .header("Origin", "http://evil.example")
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hi\"}")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, resp.statusCode(), "a foreign Origin on a state-changing POST must be refused");
        });
    }

    @Test
    void promptPostRunsAnAgentTurnAndAcceptsWith204() throws Exception {
        withServer((ts, client, base) -> {
            ts.llm().enqueue(FauxLlm.Response.contentWithUsage("hi back"));
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hello\"}")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(204, resp.statusCode());
            Await.until(() -> !ts.session().isBusy(), Duration.ofSeconds(5), "the turn runs");
            assertFalse(ts.session().userMessages().isEmpty(), "the prompt became a turn");
        });
    }

    @Test
    void blankPromptIsAcceptedWithoutStartingATurn() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"\"}")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(204, resp.statusCode());
            assertTrue(ts.session().userMessages().isEmpty(), "a blank prompt starts no turn");
        });
    }

    @Test
    void answerEndpointResolvesAndReturns204() throws Exception {
        withServer((ts, client, base) -> {
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/answer"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(
                                                                            "{\"id\":\"q1\",\"value\":\"yes\"}"))
                                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, resp.statusCode());
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
            assertEquals(200, ok.statusCode(), "valid ?t= token → page served directly (no redirect)");
            assertTrue(ok.headers().firstValue("Set-Cookie").orElse("").contains("pijota_rc="),
                                            "pairs the browser by pinning the cookie");
        }
    }

    @Test
    void tokenGateAcceptsTheCookieRejectsWrongTokensAndGatesPosts() throws Exception {
        var ts = TestSession.in(tempDir).build();
        int port = freePort();
        String token = "secret-pairing-xyz";
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of(), token)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;

            var wrongQuery = client.send(HttpRequest.newBuilder(URI.create(base + "/?t=nope")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, wrongQuery.statusCode(), "wrong ?t= token → refused");

            var viaCookie = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                                            .header("Cookie", "pijota_rc=" + token).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, viaCookie.statusCode(), "a valid cookie authenticates without ?t=");

            var badCookie = client.send(HttpRequest.newBuilder(URI.create(base + "/events"))
                                            .header("Cookie", "pijota_rc=bad").build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, badCookie.statusCode(), "wrong cookie → refused");

            var post = client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hi\"}")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, post.statusCode(), "POST endpoints are token-gated too (auth before method/CSRF checks)");
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
    void liveAgentEventsAreStreamedToTheBrowserOverSse() throws Exception {
        withServer((ts, client, base) -> {
            ts.llm().enqueue(FauxLlm.Response.contentWithUsage("streamed answer"));
            // open the SSE stream first so the connection is registered before the turn fires
            var events = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
            readUntil(events.body(), "event: history", Duration.ofSeconds(5)); // drain the initial replay frames

            client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hi\"}")).build(),
                                            HttpResponse.BodyHandlers.ofString());

            // the agent's reply must arrive as live SSE frames (onEvent → broadcast)
            String live = readUntil(events.body(), "streamed answer", Duration.ofSeconds(5));
            assertTrue(live.contains("streamed answer"), "the live answer is pushed over SSE: " + live);
        });
    }

    @Test
    void historyFrameReplaysAPriorTurnsMessages() throws Exception {
        var ts = TestSession.in(tempDir).tool(echoTool()).build();
        int port = freePort();
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of())) {
            server.start();
            ts.llm().enqueue(FauxLlm.Response.toolCall(new ToolCall("t1", "echo", "{\"text\":\"hi\"}")))
                                            .enqueue(FauxLlm.Response.contentWithUsage("the answer"));
            ts.session().prompt("the question", List.of()).get(5, TimeUnit.SECONDS);

            var client = HttpClient.newHttpClient();
            var resp = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
            String frames = readUntil(resp.body(), "event: history", Duration.ofSeconds(5));
            assertTrue(frames.contains("the question"), "user message replayed: " + frames);
            assertTrue(frames.contains("the answer"), "assistant message replayed");
        }
    }

    @Test
    void toolCallEventsReachTheBrowserOverSse() throws Exception {
        var ts = TestSession.in(tempDir).tool(echoTool()).build();
        int port = freePort();
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of())) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            ts.llm().enqueue(FauxLlm.Response.toolCall(new ToolCall("t1", "echo", "{\"text\":\"hi\"}")))
                                            .enqueue(FauxLlm.Response.contentWithUsage("done"));
            var events = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
            readUntil(events.body(), "event: history", Duration.ofSeconds(5));
            client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"use the tool\"}"))
                                            .build(), HttpResponse.BodyHandlers.ofString());
            String live = readUntil(events.body(), "tool", Duration.ofSeconds(5));
            assertTrue(live.contains("tool"), "a tool event reaches the browser: " + live);
        }
    }

    @Test
    void bashExecutionUpdateEventsReachTheBrowserOverSse() throws Exception {
        var ts = TestSession.in(tempDir).tool(progressBashTool()).build();
        int port = freePort();
        try (var server = new WebServer(ts.session(), "127.0.0.1", port, ts.llm(), List::of, List.of())) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            ts.llm().enqueue(FauxLlm.Response.toolCall(new ToolCall("t1", "bash", "{\"command\":\"echo hi\"}")))
                                            .enqueue(FauxLlm.Response.contentWithUsage("done"));
            var events = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
            readUntil(events.body(), "event: history", Duration.ofSeconds(5));

            client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"use bash\"}"))
                                            .build(), HttpResponse.BodyHandlers.ofString());

            String live = readUntil(events.body(), "bash_execution_update", Duration.ofSeconds(5));
            assertTrue(live.contains("bash_execution_update"), live);
            assertTrue(live.contains("chunk-one"), live);
        }
    }

    private static Tool echoTool() {
        return new Tool.ToolDefinition("echo", "Echoes input", Map.of(),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool();
    }

    private static Tool progressBashTool() {
        return new Tool() {
            @Override
            public String name() {
                return "bash";
            }

            @Override
            public String description() {
                return "bash";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")), "required",
                                                List.of("command"));
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Tool.Args args, String sessionCwd,
                                            java.util.function.Consumer<String> onProgress) {
                onProgress.accept("chunk-one");
                return CompletableFuture.completedFuture(Tool.ToolResult.success("done"));
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Tool.Args args, String sessionCwd) {
                return CompletableFuture.completedFuture(Tool.ToolResult.success("done"));
            }
        };
    }

    @Test
    void aSlashCommandViaPromptIsRoutedToRunCommand() throws Exception {
        withServer((ts, client, base) -> {
            var events = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
            readUntil(events.body(), "event: history", Duration.ofSeconds(5));
            // no commands are registered on this server, so an unknown /cmd is reported back (covers runCommand)
            client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"/nope\"}")).build(),
                                            HttpResponse.BodyHandlers.ofString());
            String live = readUntil(events.body(), "unknown command", Duration.ofSeconds(5));
            assertTrue(live.contains("unknown command"), live);
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

    @Test
    void promptWithAnImageAttachmentParsesAndStoresIt() throws Exception {
        withServer((ts, client, base) -> {
            ts.llm().enqueue(FauxLlm.Response.contentWithUsage("seen"));

            String body = "{\"text\":\"what is this\",\"files\":[{\"mime\":\"image/png\",\"data\":\"QQ==\","
                                            + "\"filename\":\"x.png\"}]}";
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/prompt"))
                                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                                            HttpResponse.BodyHandlers.ofString());
            assertEquals(204, resp.statusCode());

            Await.until(() -> !ts.session().userMessages().isEmpty(), Duration.ofSeconds(5), "no user message stored");
            var user = ts.session().userMessages().getFirst();
            assertEquals(1, user.attachments().size());
            assertEquals("image/png", user.attachments().getFirst().mime());
            assertEquals("x.png", user.attachments().getFirst().filename());
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
        } catch (TimeoutException e) {
            return ""; // marker never arrived; caller asserts
        }
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
