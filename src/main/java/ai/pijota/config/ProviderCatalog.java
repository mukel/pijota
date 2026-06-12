package ai.pijota.config;

import ai.pijota.util.Util;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProviderCatalog {
    private static final Catalog CATALOG = load();

    private ProviderCatalog() {}

    public static List<Provider> providers() {
        return CATALOG.providers();
    }

    public static Optional<Provider> find(String id) {
        return providers().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public static String apiType(String provider) {
        return find(provider).map(Provider::apiType).orElse(null);
    }

    public static String baseUrl(String provider) {
        return find(provider).map(Provider::baseUrl).orElse(null);
    }

    public static List<String> envNames(String provider) {
        return find(provider).map(Provider::env).filter(v -> v != null && !v.isEmpty()).orElse(List.of());
    }

    public static String defaultModel(String provider) {
        return find(provider).map(Provider::defaultModel).orElse(null);
    }

    public static Map<String, String> headers(String provider) {
        return find(provider).map(Provider::headers).filter(v -> v != null && !v.isEmpty()).orElse(Map.of());
    }

    public static Optional<OAuth> oauth(String provider) {
        return find(provider).map(Provider::oauth);
    }

    private static Catalog load() {
        try (InputStream in = ProviderCatalog.class.getResourceAsStream("/providers.json")) {
            if (in == null)
                return new Catalog(List.of());
            return Util.MAPPER.readValue(in, Catalog.class);
        } catch (Exception e) {
            return new Catalog(List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Catalog(List<Provider> providers) {
        public Catalog {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provider(String id, String displayName, String apiType, String baseUrl, List<String> env,
                                    String defaultModel, Map<String, String> headers, OAuth oauth) {
        public Provider {
            env = env == null ? List.of() : List.copyOf(env);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuth(String deviceEndpoint, String tokenEndpoint, String oauthTokenEndpoint, String clientId,
                                    String scope) {
    }
}
