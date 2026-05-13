package ai.pijota.config;

import ai.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    public String getAccessToken(String provider) {
        var entry = tokens.get(provider);
        if (entry == null) return null;
        if (entry.isExpired()) return null;
        return entry.access();
    }

    public String getRefreshToken(String provider) {
        var entry = tokens.get(provider);
        return entry != null ? entry.refresh() : null;
    }

    public void save(String provider, String accessToken, String refreshToken, long expiresAt, String accountId) {
        tokens.put(provider, new Entry(accessToken, refreshToken, expiresAt, accountId));
        persist();
    }

    public void remove(String provider) {
        tokens.remove(provider);
        persist();
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
    public record Entry(String access, String refresh, long expires, String accountId) {
        public boolean isExpired() {
            return expires > 0 && System.currentTimeMillis() > expires;
        }
    }
}
