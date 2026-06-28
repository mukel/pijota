package ai.pijota.config;

import ai.pijota.util.Util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public final class OAuthLogin {
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    public record DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresIn) {
    }

    public record TokenResult(String accessToken, String refreshToken, long expiresAt, String accountId) {
    }

    private OAuthLogin() {}

    public static TokenResult login(String deviceEndpoint, String tokenEndpoint, String clientId, String scope,
                                    Consumer<String> out) throws Exception {
        return login(deviceEndpoint, tokenEndpoint, clientId, scope, out, HttpClient.newHttpClient());
    }

    /** Injectable-client variant — lets tests drive the device-code → poll flow against a fake HTTP client. */
    static TokenResult login(String deviceEndpoint, String tokenEndpoint, String clientId, String scope,
                                    Consumer<String> out, HttpClient client) throws Exception {
        var device = requestDeviceCode(client, deviceEndpoint, clientId, scope);
        announce(out, device);
        return pollForToken(client, tokenEndpoint, clientId, device.deviceCode());
    }

    public static TokenResult loginOpenAI(String deviceCodeUrl, String tokenUrl, String oauthTokenUrl, String clientId,
                                    Consumer<String> out) throws Exception {
        return loginOpenAI(deviceCodeUrl, tokenUrl, oauthTokenUrl, clientId, out, HttpClient.newHttpClient());
    }

    /** Injectable-client variant — lets tests drive the device-auth (and code-exchange) flow against a fake client. */
    static TokenResult loginOpenAI(String deviceCodeUrl, String tokenUrl, String oauthTokenUrl, String clientId,
                                    Consumer<String> out, HttpClient client) throws Exception {
        var device = requestOpenAIDeviceCode(client, deviceCodeUrl, clientId);
        announce(out, device);

        var result = pollOpenAIDeviceAuth(client, tokenUrl, device);
        if (result.token() != null)
            return result.token();
        var authCode = result.authCode();
        return exchangeOpenAIAuthCode(client, oauthTokenUrl, clientId, authCode.code, authCode.codeVerifier);
    }

    public record AuthCode(String code, String codeVerifier) {
    }
    private record DeviceAuthResult(TokenResult token, AuthCode authCode) {
    }

    private static void announce(Consumer<String> out, DeviceCode device) {
        out.accept("");
        out.accept("Open this URL in your browser:");
        out.accept(clickableLink(device.verificationUri(), device.verificationUri()));
        out.accept("Enter this code: " + device.userCode());
        out.accept("Waiting for authorization (Esc to cancel)...");
    }

    private static DeviceCode requestDeviceCode(HttpClient client, String endpoint, String clientId, String scope)
                                    throws Exception {
        String body = "client_id=" + urlEncode(clientId) + "&scope=" + urlEncode(scope);
        var request = HttpRequest.newBuilder().uri(URI.create(endpoint))
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Device code request failed: HTTP " + response.statusCode() + " - "
                                            + response.body());
        }
        var node = Util.MAPPER.readTree(response.body());
        return new DeviceCode(node.get("device_code").asText(), node.get("user_code").asText(),
                                        node.get("verification_uri").asText(),
                                        node.has("expires_in") ? node.get("expires_in").asInt() : 300);
    }

    private static TokenResult pollForToken(HttpClient client, String endpoint, String clientId, String deviceCode)
                                    throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String body = "grant_type=urn:ietf:params:oauth:grant-type:device_code" + "&client_id="
                                            + urlEncode(clientId) + "&device_code=" + urlEncode(deviceCode);
            var request = HttpRequest.newBuilder().uri(URI.create(endpoint))
                                            .header("Content-Type", "application/x-www-form-urlencoded")
                                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = Util.MAPPER.readTree(response.body());
                if (node.has("access_token")) {
                    return buildTokenResult(node);
                }
            }
            if (response.statusCode() != 400 && response.statusCode() != 403) {
                throw new IOException("Token request failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IOException("OAuth authorization timed out");
    }

    private static DeviceCode requestOpenAIDeviceCode(HttpClient client, String endpoint, String clientId)
                                    throws Exception {
        var body = Util.MAPPER.createObjectNode().put("client_id", clientId).toString();
        var request = HttpRequest.newBuilder().uri(URI.create(endpoint)).header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException("Device code login not enabled. Enable it in ChatGPT Settings → Security →\n"
                                            + "\"Allow device code authorization\" (under Codex), then try again.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI device code request failed: HTTP " + response.statusCode() + " - "
                                            + response.body());
        }
        var node = Util.MAPPER.readTree(response.body());
        return new DeviceCode(node.get("device_auth_id").asText(), node.get("user_code").asText(),
                                        node.has("verification_uri")
                                                                        ? node.get("verification_uri").asText()
                                                                        : "https://auth.openai.com/codex/device",
                                        node.has("interval") ? node.get("interval").asInt() : 5);
    }

    private static DeviceAuthResult pollOpenAIDeviceAuth(HttpClient client, String endpoint, DeviceCode device)
                                    throws Exception {
        var interval = Duration.ofSeconds(Math.max(device.expiresIn(), 3));
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var body = Util.MAPPER.createObjectNode().put("device_auth_id", device.deviceCode())
                                            .put("user_code", device.userCode()).toString();
            var request = HttpRequest.newBuilder().uri(URI.create(endpoint)).header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = Util.MAPPER.readTree(response.body());
                if (node.has("access_token")) {
                    return new DeviceAuthResult(buildTokenResult(node), null);
                }
                if (node.has("authorization_code") && node.has("code_verifier")) {
                    return new DeviceAuthResult(null, new AuthCode(node.get("authorization_code").asText(),
                                                    node.get("code_verifier").asText()));
                }
            }
            if (response.statusCode() != 403 && response.statusCode() != 404) {
                throw new IOException("OpenAI device auth polling failed: HTTP " + response.statusCode() + " - "
                                                + response.body());
            }
            Thread.sleep(interval.toMillis());
        }
        throw new IOException("OpenAI OAuth authorization timed out");
    }

    private static TokenResult exchangeOpenAIAuthCode(HttpClient client, String tokenUrl, String clientId, String code,
                                    String codeVerifier) throws Exception {
        String redirectUri = "https://auth.openai.com/deviceauth/callback";
        String body = "grant_type=authorization_code" + "&client_id=" + urlEncode(clientId) + "&code=" + urlEncode(code)
                                        + "&code_verifier=" + urlEncode(codeVerifier) + "&redirect_uri="
                                        + urlEncode(redirectUri);
        var request = HttpRequest.newBuilder().uri(URI.create(tokenUrl))
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI token exchange failed: HTTP " + response.statusCode() + " - "
                                            + response.body());
        }
        var node = Util.MAPPER.readTree(response.body());
        if (!node.has("access_token")) {
            throw new IOException("OpenAI token exchange missing access_token: " + response.body());
        }
        return buildTokenResult(node);
    }

    /**
     * Refreshes an expired access token. Works for both standard OAuth (tokenEndpoint) and OpenAI OAuth
     * (oauthTokenEndpoint) — just pass the appropriate URL as tokenUrl.
     */
    public static TokenResult refreshToken(String tokenUrl, String clientId, String refreshToken) throws Exception {
        var client = HttpClient.newHttpClient();
        String body = "grant_type=refresh_token" + "&client_id=" + urlEncode(clientId) + "&refresh_token="
                                        + urlEncode(refreshToken);
        var request = HttpRequest.newBuilder().uri(URI.create(tokenUrl))
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(30))
                                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Token refresh failed: HTTP " + response.statusCode() + " - " + response.body());
        }
        var node = Util.MAPPER.readTree(response.body());
        if (!node.has("access_token")) {
            throw new IOException("Token refresh missing access_token: " + response.body());
        }
        return buildTokenResult(node);
    }

    private static TokenResult buildTokenResult(com.fasterxml.jackson.databind.JsonNode node) {
        return buildTokenResult(node, System.currentTimeMillis());
    }

    static TokenResult buildTokenResult(com.fasterxml.jackson.databind.JsonNode node, long nowMs) {
        String accessToken = node.get("access_token").asText();
        String refreshToken = node.has("refresh_token") ? node.get("refresh_token").asText() : null;
        int expiresIn = node.has("expires_in") ? node.get("expires_in").asInt() : 3600;
        long expiresAt = nowMs + (expiresIn * 1000L);
        String accountId = node.has("account_id") ? node.get("account_id").asText() : null;
        // ChatGPT/Codex OAuth doesn't return a top-level account_id — it's a claim inside the id_token JWT.
        if ((accountId == null || accountId.isBlank()) && node.hasNonNull("id_token"))
            accountId = chatgptAccountIdFromIdToken(node.get("id_token").asText());
        return new TokenResult(accessToken, refreshToken, expiresAt, accountId);
    }

    /**
     * Extracts {@code https://api.openai.com/auth.chatgpt_account_id} from an OIDC id_token; null if absent/malformed.
     */
    static String chatgptAccountIdFromIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3)
                return null;
            int pad = (4 - parts[1].length() % 4) % 4;
            // Parse via String, not byte[]: Jackson's byte[] path fails under a native image (build-time init).
            String json = new String(java.util.Base64.getUrlDecoder().decode(parts[1] + "=".repeat(pad)),
                                            java.nio.charset.StandardCharsets.UTF_8);
            var auth = Util.MAPPER.readTree(json).get("https://api.openai.com/auth");
            return auth != null && auth.has("chatgpt_account_id") ? auth.get("chatgpt_account_id").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    static String clickableLink(String url, String text) {
        return "\033]8;;" + url + "\033\\" + text + "\033]8;;\033\\";
    }
}
