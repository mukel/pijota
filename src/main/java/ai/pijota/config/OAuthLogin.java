package ai.pijota.config;

import ai.pijota.util.Util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class OAuthLogin {
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    public record DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresIn) {}

    public record TokenResult(String accessToken, String refreshToken, long expiresAt, String accountId) {
        public boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private OAuthLogin() {}

    public static TokenResult login(String deviceEndpoint, String tokenEndpoint,
                                     String clientId, String scope) throws Exception {
        var client = HttpClient.newHttpClient();

        var device = requestDeviceCode(client, deviceEndpoint, clientId, scope);
        System.out.println("\nOpen this URL in your browser:");
        System.out.println(clickableLink(device.verificationUri(), device.verificationUri()));
        System.out.println("Enter this code: " + device.userCode());
        System.out.println("Waiting for authorization...\n");

        return pollForToken(client, tokenEndpoint, clientId, device.deviceCode());
    }

    public static TokenResult loginOpenAI(String deviceCodeUrl, String tokenUrl,
                                           String oauthTokenUrl, String clientId) throws Exception {
        var client = HttpClient.newHttpClient();

        var device = requestOpenAIDeviceCode(client, deviceCodeUrl, clientId);
        String uri = "https://auth.openai.com/codex/device";
        System.out.println("\nOpen this URL in your browser:");
        System.out.println(clickableLink(uri, uri));
        System.out.println("Enter this code: " + device.userCode());
        System.out.println("Waiting for authorization...\n");

        var authCode = pollOpenAIDeviceAuth(client, tokenUrl, device);
        return exchangeOpenAIAuthCode(client, oauthTokenUrl, clientId, authCode.code, authCode.codeVerifier);
    }

    public record AuthCode(String code, String codeVerifier) {}

    private static DeviceCode requestDeviceCode(HttpClient client, String endpoint,
                                                 String clientId, String scope) throws Exception {
        String body = "client_id=" + urlEncode(clientId) + "&scope=" + urlEncode(scope);
        var request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Device code request failed: HTTP " + response.statusCode()
                + " - " + response.body());
        }
        var node = Util.MAPPER.readTree(response.body());
        return new DeviceCode(
            node.get("device_code").asText(),
            node.get("user_code").asText(),
            node.get("verification_uri").asText(),
            node.has("expires_in") ? node.get("expires_in").asInt() : 300
        );
    }

    private static TokenResult pollForToken(HttpClient client, String endpoint,
                                             String clientId, String deviceCode) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String body = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                + "&client_id=" + urlEncode(clientId)
                + "&device_code=" + urlEncode(deviceCode);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = Util.MAPPER.readTree(response.body());
                if (node.has("access_token")) {
                    return buildTokenResult(node);
                }
            }
            if (response.statusCode() != 400 && response.statusCode() != 403) {
                throw new IOException("Token request failed: HTTP " + response.statusCode()
                    + " - " + response.body());
            }
            System.out.print(".");
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IOException("OAuth authorization timed out");
    }

    private static DeviceCode requestOpenAIDeviceCode(HttpClient client, String endpoint,
                                                       String clientId) throws Exception {
        var body = Util.MAPPER.createObjectNode().put("client_id", clientId).toString();
        var request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException(
                "Device code login not enabled. Enable it in ChatGPT Settings → Security →\n" +
                "\"Allow device code authorization\" (under Codex), then try again.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI device code request failed: HTTP "
                + response.statusCode() + " - " + response.body());
        }
        var node = Util.MAPPER.readTree(response.body());
        return new DeviceCode(
            node.get("device_auth_id").asText(),
            node.get("user_code").asText(),
            "https://auth.openai.com/codex/device",
            node.has("interval") ? node.get("interval").asInt() : 5
        );
    }

    private static AuthCode pollOpenAIDeviceAuth(HttpClient client, String endpoint,
                                                   DeviceCode device) throws Exception {
        var interval = Duration.ofSeconds(Math.max(device.expiresIn(), 3));
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var body = Util.MAPPER.createObjectNode()
                .put("device_auth_id", device.deviceCode())
                .put("user_code", device.userCode())
                .toString();
            var request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = Util.MAPPER.readTree(response.body());
                if (node.has("authorization_code") && node.has("code_verifier")) {
                    return new AuthCode(
                        node.get("authorization_code").asText(),
                        node.get("code_verifier").asText()
                    );
                }
                throw new IOException("Invalid device auth token response: " + response.body());
            }
            if (response.statusCode() != 403 && response.statusCode() != 404) {
                throw new IOException("OpenAI device auth polling failed: HTTP "
                    + response.statusCode() + " - " + response.body());
            }
            System.out.print(".");
            Thread.sleep(interval.toMillis());
        }
        throw new IOException("OpenAI OAuth authorization timed out");
    }

    private static TokenResult exchangeOpenAIAuthCode(HttpClient client, String tokenUrl,
                                                        String clientId, String code,
                                                        String codeVerifier) throws Exception {
        String body = "grant_type=authorization_code"
            + "&client_id=" + urlEncode(clientId)
            + "&code=" + urlEncode(code)
            + "&code_verifier=" + urlEncode(codeVerifier)
            + "&redirect_uri=" + urlEncode("http://localhost:1455/auth/callback");
        var request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI token exchange failed: HTTP "
                + response.statusCode() + " - " + response.body());
        }
        var node = Util.MAPPER.readTree(response.body());
        if (!node.has("access_token")) {
            throw new IOException("OpenAI token exchange missing access_token: " + response.body());
        }
        return buildTokenResult(node);
    }

    private static TokenResult buildTokenResult(com.fasterxml.jackson.databind.JsonNode node) {
        String accessToken = node.get("access_token").asText();
        String refreshToken = node.has("refresh_token") ? node.get("refresh_token").asText() : null;
        int expiresIn = node.has("expires_in") ? node.get("expires_in").asInt() : 3600;
        long expiresAt = System.currentTimeMillis() + (expiresIn * 1000L);
        String accountId = node.has("account_id") ? node.get("account_id").asText() : null;
        return new TokenResult(accessToken, refreshToken, expiresAt, accountId);
    }

    static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String clickableLink(String url, String text) {
        return "\033]8;;" + url + "\033\\" + text + "\033]8;;\033\\";
    }
}
