package ai.pijota.config;

import ai.pijota.util.Util;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProviderCatalog {
    private static final Catalog CATALOG = load();
    // User-defined providers from settings.json (e.g. a local OpenAI-compatible server); registered at startup and
    // overlaid on the bundled catalog by id, so they flow through every lookup (baseUrl/apiType/env/model) uniformly.
    private static volatile List<Provider> userProviders = List.of();

    private ProviderCatalog() {}

    /** Overlay user-defined providers (settings.json {@code providers}); an id reuse overrides a builtin. */
    public static void registerUserProviders(List<Provider> providers) {
        userProviders = providers == null ? List.of() : List.copyOf(providers);
    }

    public static List<Provider> providers() {
        if (userProviders.isEmpty())
            return CATALOG.providers();
        var byId = new java.util.LinkedHashMap<String, Provider>();
        for (var p : CATALOG.providers())
            byId.put(p.id(), p);
        for (var p : userProviders)
            byId.put(p.id(), p); // user entry wins on id collision
        return List.copyOf(byId.values());
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

    /**
     * How this provider expects reasoning to be toggled (e.g. {@code "qwen"} → {@code enable_thinking};
     * {@code "qwen-chat-template"} → {@code chat_template_kwargs}). {@code null} means standard OpenAI.
     */
    public static String thinkingFormat(String provider) {
        return find(provider).map(Provider::thinkingFormat).orElse(null);
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
                                    String defaultModel, Map<String, String> headers, OAuth oauth,
                                    String thinkingFormat) {
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
