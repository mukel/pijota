package org.pijota.llm;

import org.pijota.config.OAuthTokenStore;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;
import org.pijota.util.Env;
import org.pijota.util.ErrorUtil;
import org.pijota.util.Proxies;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public final class ModelRefresher {

    private static final Logger log = LoggerFactory.getLogger(ModelRefresher.class);
    private static final long DEFAULT_CONTEXT_WINDOW = 128_000L;
    private static final String CHATGPT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";
    private static final String CLIENT_VERSION = "0.1.0";

    private ModelRefresher() {}

    public record Result(int providersChecked, int modelsFound, List<String> errors) {

        /**
         * What the refresh did, in one line, for whichever frontend asked.
         *
         * <p>
         * The TUI and the CLI described the same event in two different wordings, each written where it was printed.
         * Neither said the result is kept, so the command read as something to run again every session, which is
         * exactly what it used to be before the cache. "0 models from 0 providers" was the answer whenever nothing was
         * configured: two numbers and no cause.
         */
        public String summary() {
            if (providersChecked == 0)
                return "No provider to ask: none has a credential yet, and none is configured without one.";
            String asked = Util.plural(providersChecked, "provider");
            if (modelsFound == 0)
                return "Asked " + asked + "; none offered a model list.";
            return "Found " + Util.plural(modelsFound, "model") + " from " + asked + ", remembered for next time.";
        }
    }

    public static Result refresh(SettingsManager settings) {
        var client = Proxies.httpClientBuilder().build();
        var oauth = new OAuthTokenStore();
        var models = new ArrayList<ModelInfo>();
        var errors = new ArrayList<String>();
        // Only the providers that actually answered. A provider that could not be reached has not changed its mind,
        // so its previously discovered models are kept rather than dropped.
        var answered = new LinkedHashSet<String>();
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
                answered.add(provider.id());
            } catch (Exception e) {
                errors.add(provider.id() + ": " + oneLineReason(e));
            }
        }

        models.sort(Comparator.comparing(ModelInfo::provider).thenComparing(ModelInfo::id));
        ModelRegistry.saveDiscovered(models, answered);
        return new Result(providersChecked, models.size(), List.copyOf(errors));
    }

    /**
     * Refreshes because the set of reachable providers just changed, which is the only event that changes the answer.
     *
     * <p>
     * Asking every provider what it serves is how a local or niche endpoint gets into the list at all: nothing else
     * knows about an Ollama on localhost or a company's internal gateway, so a central model archive cannot help there.
     * That is worth one request per configured provider at the moment a credential is added or removed, and nothing at
     * any other time.
     *
     * <p>
     * Best effort by design: a login must not fail because a model list could not be fetched. {@code PIJOTA_OFFLINE}
     * skips it, for machines that should never reach out on their own.
     */
    public static void afterCredentialChange(SettingsManager settings) {
        if (Env.get("pijota.offline", "PIJOTA_OFFLINE") != null)
            return;
        try {
            refresh(settings);
        } catch (Exception e) {
            log.info("Could not refresh models after the credential change: {}", oneLineReason(e));
        }
    }

    /**
     * One compact line per failed provider. {@code getMessage()} alone produced two bad outcomes here: a bare
     * {@code null} for exceptions carrying no message (unreadable), and a provider's whole JSON error body spilled
     * across the transcript for a single 401. {@link ErrorUtil#message} never yields null (and scrubs credentials);
     * squeezing whitespace keeps a refresh summary scannable when several providers fail at once.
     */
    static String oneLineReason(Exception e) {
        return Util.ellipsize(ErrorUtil.message(e).replaceAll("\\s+", " ").strip(), 160);
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
            // scrub the body: a provider's error response can echo the request (and its auth header) back
            throw new IOException("HTTP " + response.statusCode() + " - " + ErrorUtil.sanitize(response.body()));
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
            // OpenAI-compatible providers differ in how (or whether) they report context length: fireworks sends
            // context_length, others context_window / max_context_window / max_tokens, many send nothing. Take the
            // first present, else the 128k fallback. Same for image input: read the flag when offered so a vision
            // model isn't misreported as text-only.
            long contextWindow = firstLong(item, DEFAULT_CONTEXT_WINDOW, "context_length", "context_window",
                                            "max_context_window", "max_tokens");
            var input = item.path("supports_image_input").asBoolean(false) ? List.of("text", "image") : null;
            out.add(new ModelInfo(id, provider.id(), displayName(id), contextWindow, null, null, input));
        }
        return out;
    }

    /**
     * What a local server says it is currently serving, or 0 if it does not say.
     *
     * <p>
     * {@code n_ctx} belongs to the server process, not to the model: it is whatever {@code -c} that launch chose, so
     * unlike everything else in the catalogue it stops being true when the server restarts. A caller that needs it to
     * be right -- the compaction threshold -- asks again rather than trusting a remembered answer.
     *
     * <p>
     * Only ever asked of a host on this machine or network: every remote provider would 404 it, and asking would put a
     * speculative round-trip in front of each of them.
     */
    public static long servedContextWindow(HttpClient client, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || !Util.isInternalAddress(URI.create(baseUrl).getHost()))
            return 0;
        String root = baseUrl.replaceAll("/+$", "").replaceAll("/v\\d+$", "");
        try {
            var request = HttpRequest.newBuilder().uri(URI.create(root + "/props")).timeout(Duration.ofSeconds(5)).GET()
                                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return 0;
            return firstLong(Util.MAPPER.readTree(response.body()), 0, "n_ctx");
        } catch (Exception e) {
            log.debug("No /props on {}: {}", root, oneLineReason(e));
            return 0;
        }
    }

    private static List<ModelInfo> parseCodexModels(String providerId, JsonNode models) {
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
            long contextWindow = firstLong(item, DEFAULT_CONTEXT_WINDOW, "context_window", "max_context_window");
            out.add(new ModelInfo(id, providerId, displayName, contextWindow));
        }
        return out;
    }

    /** First present, long-convertible value among {@code keys}, else {@code fallback}. */
    static long firstLong(JsonNode node, long fallback, String... keys) {
        for (String key : keys)
            if (node.has(key) && node.get(key).canConvertToLong())
                return node.get(key).asLong();
        return fallback;
    }

    static String displayName(String id) {
        // Provider-served ids often carry a path prefix (fireworks: accounts/<org>/(models|routers)/<name>); title-case
        // only the model name, not the account/path boilerplate. Plain ids (gpt-4, openai/gpt-oss) are untouched: we
        // strip only when the id starts with "accounts/" and has a models/ or routers/ segment to anchor on.
        String name = id;
        if (id.startsWith("accounts/")) {
            for (String seg : new String[] {"/models/", "/routers/"}) {
                int at = id.indexOf(seg);
                if (at >= 0) {
                    name = id.substring(at + seg.length());
                    break;
                }
            }
        }
        var words = name.replace('-', ' ').replace('_', ' ').split("\\s+");
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
