package org.pijota.config;

import org.pijota.testkit.StdioCapture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the OAuth device-code flow end-to-end against a loopback {@link HttpServer} (no network): device-code
 * request, token polling, refresh, and the error path. Endpoint URLs are parameters, so the real {@link OAuthLogin}
 * runs unmodified against canned responses. {@code login()} only prints the verification prompt to stdout, captured
 * here so it stays out of the test log.
 */
class OAuthLoginFlowTest {

    @RegisterExtension
    StdioCapture stdio = new StdioCapture();

    private HttpServer server;

    private String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void route(String path, int status, String json) {
        server.createContext(path, ex -> respond(ex, status, json));
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void stop() {
        if (server != null)
            server.stop(0);
    }

    @Test
    void loginCompletesTheDeviceFlow() throws Exception {
        String base = start();
        route("/device", 200, """
                                        {"device_code":"DC","user_code":"WXYZ-1234","verification_uri":"https://example.test/verify",\
                                        "expires_in":300}""");
        route("/token", 200, """
                                        {"access_token":"AT","refresh_token":"RT","expires_in":3600,"account_id":"acct-1"}""");
        server.start();
        try {
            var token = OAuthLogin.login(base + "/device", base + "/token", "client-id", "read", System.out::println);

            assertEquals("AT", token.accessToken());
            assertEquals("RT", token.refreshToken());
            assertEquals("acct-1", token.accountId());
            assertTrue(token.expiresAt() > System.currentTimeMillis());
            // the user-facing prompt carries the verification URL and the user code
            assertTrue(stdio.out().contains("https://example.test/verify"), stdio.out());
            assertTrue(stdio.out().contains("WXYZ-1234"), stdio.out());
        } finally {
            stop();
        }
    }

    @Test
    void refreshTokenParsesRenewedTokens() throws Exception {
        String base = start();
        route("/token", 200, """
                                        {"access_token":"AT2","refresh_token":"RT2","expires_in":7200}""");
        server.start();
        try {
            var token = OAuthLogin.refreshToken(base + "/token", "client-id", "old-refresh");

            assertEquals("AT2", token.accessToken());
            assertEquals("RT2", token.refreshToken());
            assertTrue(token.expiresAt() > System.currentTimeMillis());
        } finally {
            stop();
        }
    }

    @Test
    void refreshTokenFailsOnErrorStatus() throws Exception {
        String base = start();
        route("/token", 400, """
                                        {"error":"invalid_grant"}""");
        server.start();
        try {
            var ex = assertThrows(Exception.class,
                                            () -> OAuthLogin.refreshToken(base + "/token", "client-id", "stale"));
            assertTrue(ex.getMessage() == null || ex.getMessage().contains("400")
                                            || ex.getMessage().contains("refresh"), String.valueOf(ex.getMessage()));
        } finally {
            stop();
        }
    }

    @Test
    void loginFailsWhenDeviceEndpointErrors() throws Exception {
        String base = start();
        route("/device", 500, "boom");
        server.start();
        try {
            assertThrows(IOException.class, () -> OAuthLogin.login(base + "/device", base + "/token", "client-id",
                                            "read", System.out::println));
        } finally {
            stop();
        }
    }
}
