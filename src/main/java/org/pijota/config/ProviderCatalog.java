package org.pijota.config;

import org.pijota.util.Util;
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

    public static List<String> validateUserProviders(List<Provider> providers) {
        if (providers == null || providers.isEmpty())
            return List.of();
        var warnings = new java.util.ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        for (var p : providers) {
            if (p == null) {
                warnings.add("settings.providers contains a null entry");
                continue;
            }
            if (p.id() == null || p.id().isBlank())
                warnings.add("settings.providers contains an entry without id");
            else if (!seen.add(p.id()))
                warnings.add("settings.providers contains duplicate provider id: " + p.id());
            if (p.apiType() == null || p.apiType().isBlank())
                warnings.add("settings.providers." + safeId(p) + " is missing apiType");
            if (p.defaultModel() == null || p.defaultModel().isBlank())
                warnings.add("settings.providers." + safeId(p) + " is missing defaultModel");
        }
        return List.copyOf(warnings);
    }

    private static String safeId(Provider p) {
        return p.id() == null || p.id().isBlank() ? "<unknown>" : p.id();
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
        return find(provider).map(Provider::baseUrl).map(ProviderCatalog::interpolateEnv).orElse(null);
    }

    public static List<String> envNames(String provider) {
        return find(provider).map(Provider::env).filter(v -> v != null && !v.isEmpty()).orElse(List.of());
    }

    public static String defaultModel(String provider) {
        return find(provider).map(Provider::defaultModel).orElse(null);
    }

    public static Map<String, String> headers(String provider) {
        return find(provider).map(Provider::headers).filter(v -> v != null && !v.isEmpty()).map(headers -> {
            var out = new java.util.LinkedHashMap<String, String>();
            headers.forEach((k, v) -> out.put(k, interpolateEnv(v)));
            return Map.copyOf(out);
        }).orElse(Map.of());
    }

    public static String interpolateEnv(String value) {
        if (value == null || value.isBlank())
            return value;
        var out = new StringBuilder();
        for (int i = 0; i < value.length();) {
            char ch = value.charAt(i);
            if (ch != '$') {
                out.append(ch);
                i++;
                continue;
            }
            if (i + 1 < value.length() && value.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }
            int start = i + 1;
            int end = start;
            if (start < value.length() && value.charAt(start) == '{') {
                end = value.indexOf('}', start + 1);
                if (end < 0) {
                    out.append(ch);
                    i++;
                    continue;
                }
                String env = System.getenv(value.substring(start + 1, end));
                if (env != null)
                    out.append(env);
                i = end + 1;
                continue;
            }
            while (end < value.length() && (Character.isLetterOrDigit(value.charAt(end)) || value.charAt(end) == '_'))
                end++;
            if (end == start) {
                out.append(ch);
                i++;
                continue;
            }
            String env = System.getenv(value.substring(start, end));
            if (env != null)
                out.append(env);
            i = end;
        }
        return out.toString();
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
