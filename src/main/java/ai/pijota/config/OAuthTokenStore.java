package ai.pijota.config;

import ai.pijota.util.ErrorUtil;
import ai.pijota.util.PijotaHome;
import ai.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class OAuthTokenStore {
    private static final Logger logger = LoggerFactory.getLogger(OAuthTokenStore.class);

    private final Path authFile;
    private final Map<String, Entry> tokens;

    public OAuthTokenStore() {
        this(PijotaHome.resolve("auth.json"));
    }

    OAuthTokenStore(Path authFile) {
        this.authFile = authFile;
        this.tokens = loadAll(authFile);
    }

    /**
     * Returns a valid access token, refreshing it automatically if expired. Returns null if no token is available or
     * refresh fails.
     */
    public String getAccessToken(String provider) {
        var entry = tokens.get(provider);
        if (entry == null)
            return null;
        if (!entry.isExpired())
            return entry.access();
        return tryRefresh(provider, entry);
    }

    public String getRefreshToken(String provider) {
        var entry = tokens.get(provider);
        return entry != null ? entry.refresh() : null;
    }

    public String getAccountId(String provider) {
        var entry = tokens.get(provider);
        return entry != null ? entry.accountId() : null;
    }

    public void save(String provider, String accessToken, String refreshToken, long expiresAt, String accountId,
                                    String tokenUrl, String clientId) {
        tokens.put(provider, new Entry(accessToken, refreshToken, expiresAt, accountId, tokenUrl, clientId));
        persist();
    }

    public void remove(String provider) {
        tokens.remove(provider);
        persist();
    }

    private String tryRefresh(String provider, Entry entry) {
        if (entry.refresh() == null || entry.refresh().isBlank())
            return null;
        if (entry.tokenUrl() == null || entry.tokenUrl().isBlank())
            return null;

        try {
            var result = OAuthLogin.refreshToken(entry.tokenUrl(), entry.clientId(), entry.refresh());
            tokens.put(provider, new Entry(result.accessToken(), result.refreshToken(), result.expiresAt(),
                                            result.accountId(), entry.tokenUrl(), entry.clientId()));
            persist();
            return result.accessToken();
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("400") || msg.contains("401") || msg.contains("403") || msg.contains("invalid_grant")) {
                // refresh token invalid/revoked — clear it so the user is prompted to re-login
                tokens.remove(provider);
                persist();
                logger.warn("OAuth refresh token for {} was rejected; cleared it — re-login required", provider);
            } else {
                // a transient network/IO error otherwise vanishes as a null token, looking like a revoked login
                logger.warn("OAuth token refresh for {} failed (network/IO): {}", provider, ErrorUtil.message(e));
            }
            return null;
        } catch (Exception e) {
            logger.warn("OAuth token refresh failed for {}: {}", provider, ErrorUtil.message(e));
            return null;
        }
    }

    private void persist() {
        try {
            Files.createDirectories(authFile.getParent());
            Util.restrictToOwner(authFile.getParent());
            Util.MAPPER.writerWithDefaultPrettyPrinter().writeValue(authFile.toFile(), tokens);
            Util.restrictToOwner(authFile); // OAuth access/refresh tokens — owner-only
        } catch (IOException e) {
            logger.warn("Failed to persist auth tokens: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Entry> loadAll(Path authFile) {
        try {
            if (!Files.exists(authFile))
                return new HashMap<>();
            var type = Util.MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Entry.class);
            Map<String, Entry> map = Util.MAPPER.readValue(authFile.toFile(), type);
            return new HashMap<>(map != null ? map : Collections.emptyMap());
        } catch (Exception e) {
            // corrupt auth.json otherwise silently logs everyone out — say so (no token contents, just the reason)
            logger.warn("Could not read stored auth tokens ({}); starting with an empty store", ErrorUtil.message(e));
            return new HashMap<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String access, String refresh, long expires, @JsonProperty("account_id") String accountId,
                                    @JsonProperty("token_url") String tokenUrl,
                                    @JsonProperty("client_id") String clientId) {
        public boolean isExpired() {
            return expires > 0 && System.currentTimeMillis() > expires;
        }
    }
}
