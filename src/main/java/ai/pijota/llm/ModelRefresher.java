package ai.pijota.llm;

import ai.pijota.config.SettingsManager;
import ai.pijota.config.OAuthTokenStore;
import ai.pijota.config.ProviderCatalog;
import ai.pijota.util.Util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ModelRefresher {
    private static final long DEFAULT_CONTEXT_WINDOW = 128_000L;
    private static final String CHATGPT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";
    private static final String CLIENT_VERSION = "0.1.0";

    private ModelRefresher() {}

    public record Result(int providersChecked, int modelsFound, List<String> errors) {
    }

    public static Result refresh(SettingsManager settings) {
        var client = HttpClient.newHttpClient();
        var oauth = new OAuthTokenStore();
        var models = new ArrayList<ModelInfo>();
        var errors = new ArrayList<String>();
        int providersChecked = 0;

        for (var provider : ProviderCatalog.providers()) {
            String token = settings != null ? settings.resolveApiKey(provider.id()) : null;
            if (token == null)
                continue;
            if (!"openai".equals(ProviderCatalog.apiType(provider.id())))
                continue;
            String accountId = oauth.getAccountId(provider.id());
            boolean oauthToken = accountId != null && !accountId.isBlank();

            providersChecked++;
            try {
                models.addAll(fetchOpenAiCompatible(client, provider, token, accountId, oauthToken));
            } catch (Exception e) {
                errors.add(provider.id() + ": " + e.getMessage());
            }
        }

        models.sort(Comparator.comparing(ModelInfo::provider).thenComparing(ModelInfo::id));
        ModelRegistry.replaceDiscovered(models);
        return new Result(providersChecked, models.size(), List.copyOf(errors));
    }

    // package-private: tested directly with an injected (fake) HttpClient against canned /models responses
    static List<ModelInfo> fetchOpenAiCompatible(HttpClient client, ProviderCatalog.Provider provider, String token,
                                    String accountId, boolean oauthToken) throws Exception {
        String baseUrl = oauthToken ? CHATGPT_CODEX_BASE_URL : provider.baseUrl();
        if (baseUrl == null || baseUrl.isBlank())
            return List.of();
        String url = baseUrl.replaceAll("/+$", "") + "/models?client_version=" + CLIENT_VERSION;
        var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                                        .header("Authorization", "Bearer " + token).header("originator", "pijota")
                                        .header("OpenAI-Beta", "responses=experimental").GET();
        if (oauthToken && accountId != null && !accountId.isBlank()) {
            request.header("ChatGPT-Account-Id", accountId);
        }
        var builtRequest = request.build();
        var response = client.send(builtRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new java.io.IOException("HTTP " + response.statusCode() + " - " + response.body());
        }

        var node = Util.MAPPER.readTree(response.body());
        if (node.has("models") && node.get("models").isArray()) {
            return parseCodexModels(provider.id(), node.get("models"));
        }
        var data = node.get("data");
        if (data == null || !data.isArray())
            return List.of();

        var out = new ArrayList<ModelInfo>();
        for (var item : data) {
            var idNode = item.get("id");
            if (idNode == null || idNode.asText().isBlank())
                continue;
            String id = idNode.asText();
            out.add(new ModelInfo(id, provider.id(), displayName(id), DEFAULT_CONTEXT_WINDOW));
        }
        return out;
    }

    private static List<ModelInfo> parseCodexModels(String providerId, com.fasterxml.jackson.databind.JsonNode models) {
        var out = new ArrayList<ModelInfo>();
        for (var item : models) {
            var slug = item.get("slug");
            if (slug == null || slug.asText().isBlank())
                continue;
            if (item.has("visibility") && "none".equals(item.get("visibility").asText()))
                continue;
            String id = slug.asText();
            String displayName = item.has("display_name") && !item.get("display_name").asText().isBlank()
                                            ? item.get("display_name").asText()
                                            : displayName(id);
            long contextWindow = firstLong(item, "context_window", "max_context_window", DEFAULT_CONTEXT_WINDOW);
            out.add(new ModelInfo(id, providerId, displayName, contextWindow));
        }
        return out;
    }

    static long firstLong(com.fasterxml.jackson.databind.JsonNode node, String first, String second, long fallback) {
        if (node.has(first) && node.get(first).canConvertToLong())
            return node.get(first).asLong();
        if (node.has(second) && node.get(second).canConvertToLong())
            return node.get(second).asLong();
        return fallback;
    }

    static String displayName(String id) {
        var words = id.replace('-', ' ').replace('_', ' ').split("\\s+");
        var out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank())
                continue;
            if (!out.isEmpty())
                out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? id : out.toString();
    }
}
