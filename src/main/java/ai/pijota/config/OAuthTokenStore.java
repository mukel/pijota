package ai.pijota.config;

import ai.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class OAuthTokenStore {
    private static final Path CONFIG_HOME = Path.of(System.getProperty("user.home"), ".pijota");
    private static final Path AUTH_FILE = CONFIG_HOME.resolve("auth.json");

    private final Map<String, Entry> tokens;

    public OAuthTokenStore() {
        this.tokens = loadAll();
    }

    /**
     * Returns a valid access token, refreshing it automatically if expired.
     * Returns null if no token is available or refresh fails.
     */
    public String getAccessToken(String provider) {
        var entry = tokens.get(provider);
        if (entry == null) return null;
        if (!entry.isExpired()) return entry.access();
        return tryRefresh(provider, entry);
    }

    public String getRefreshToken(String provider) {
        var entry = tokens.get(provider);
        return entry != null ? entry.refresh() : null;
    }

    public void save(String provider, String accessToken, String refreshToken,
                     long expiresAt, String accountId,
                     String tokenUrl, String clientId) {
        tokens.put(provider,
            new Entry(accessToken, refreshToken, expiresAt, accountId, tokenUrl, clientId));
        persist();
    }

    public void remove(String provider) {
        tokens.remove(provider);
        persist();
    }

    private String tryRefresh(String provider, Entry entry) {
        if (entry.refresh() == null || entry.refresh().isBlank()) return null;
        if (entry.tokenUrl() == null || entry.tokenUrl().isBlank()) return null;

        try {
            var result = OAuthLogin.refreshToken(
                entry.tokenUrl(), entry.clientId(), entry.refresh());
            tokens.put(provider,
                new Entry(result.accessToken(), result.refreshToken(),
                    result.expiresAt(), result.accountId(),
                    entry.tokenUrl(), entry.clientId()));
            persist();
            return result.accessToken();
        } catch (IOException e) {
            // If the refresh token is invalid/revoked, clean up
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("400") || msg.contains("401") || msg.contains("403")
                || msg.contains("invalid_grant")) {
                tokens.remove(provider);
                persist();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void persist() {
        try {
            Files.createDirectories(CONFIG_HOME);
            Util.MAPPER.writerWithDefaultPrettyPrinter().writeValue(AUTH_FILE.toFile(), tokens);
        } catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Entry> loadAll() {
        try {
            if (!Files.exists(AUTH_FILE)) return new HashMap<>();
            var type = Util.MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Entry.class);
            Map<String, Entry> map = Util.MAPPER.readValue(AUTH_FILE.toFile(), type);
            return new HashMap<>(map != null ? map : Collections.emptyMap());
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
        String access,
        String refresh,
        long expires,
        @JsonProperty("account_id") String accountId,
        @JsonProperty("token_url") String tokenUrl,
        @JsonProperty("client_id") String clientId
    ) {
        public boolean isExpired() {
            return expires > 0 && System.currentTimeMillis() > expires;
        }
    }
}
