package org.pijota.llm;

import org.pijota.config.ProviderCatalog;
import org.pijota.util.Proxies;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface ProviderFactory {

    StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                    ThinkingLevel thinkingLevel);

    default boolean supports(String provider) {
        return false;
    }

    /**
     * A langchain4j HTTP client builder that routes through the configured proxy, or {@code null} when no proxy is set
     * (leaving langchain4j's default client). Explicitly instantiating the JDK builder also avoids a ServiceLoader
     * lookup that would need extra native-image metadata.
     */
    static HttpClientBuilder proxiedClientBuilder() {
        var selector = Proxies.selector();
        return selector == null
                                        ? null
                                        : new JdkHttpClientBuilder().httpClientBuilder(
                                                                        HttpClient.newBuilder().proxy(selector));
    }

    /** Point a langchain4j model builder's {@code httpClientBuilder} at the configured proxy, if one is set. */
    private static void applyProxy(Consumer<HttpClientBuilder> httpClientBuilder) {
        var proxy = proxiedClientBuilder();
        if (proxy != null)
            httpClientBuilder.accept(proxy);
    }

    /**
     * Extra OpenAI request-body params some compatible runtimes need to toggle reasoning (llama.cpp / vLLM / Qwen chat
     * templates), selected by the provider's declared {@code thinkingFormat}. Standard OpenAI providers declare none
     * and get an empty map.
     */
    static Map<String, Object> thinkingParameters(String thinkingFormat, boolean enabled) {
        if (thinkingFormat == null)
            return Map.of();
        return switch (thinkingFormat) {
            case "qwen" -> Map.of("enable_thinking", enabled);
            case "qwen-chat-template" ->
                Map.of("enable_thinking", enabled, "chat_template_kwargs", Map.of("enable_thinking", enabled));
            default -> Map.of();
        };
    }

    record OpenAiCompatible() implements ProviderFactory {
        @Override
        public StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                        ThinkingLevel thinkingLevel) {
            if ("openai-codex".equals(providerName)) {
                return new CodexStreamingChatModel(baseUrl, apiKey, modelName, providerName, thinkingLevel);
            }
            boolean thinkingOn = thinkingLevel != null && thinkingLevel != ThinkingLevel.OFF;
            var builder = OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                                            .timeout(LlmTimeouts.request());
            applyProxy(builder::httpClientBuilder);
            if (thinkingOn)
                builder.returnThinking(true);
            var thinkingParams = thinkingParameters(ProviderCatalog.thinkingFormat(providerName), thinkingOn);
            if (!thinkingParams.isEmpty())
                builder.customParameters(thinkingParams);
            return builder.build();
        }

        @Override
        public boolean supports(String p) {
            String apiType = ProviderCatalog.apiType(p);
            return apiType == null || "openai".equals(apiType);
        }
    }

    record GoogleProvider() implements ProviderFactory {
        @Override
        public StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                        ThinkingLevel thinkingLevel) {
            return GoogleAiGeminiStreamingChatModel.builder().apiKey(apiKey).modelName(modelName)
                                            .timeout(LlmTimeouts.request()).build();
        }

        @Override
        public boolean supports(String p) {
            String apiType = ProviderCatalog.apiType(p);
            return apiType == null ? "google".equals(p) : "google".equals(apiType);
        }
    }

    /**
     * The output ceiling for an Anthropic-shaped request, which the wire format requires and does not default.
     *
     * <p>
     * It was pinned at 4096, low enough that writing a page of HTML ran out of room mid-tool-call. That truncation is
     * not a graceful stop: the arguments arrive as unterminated JSON, the tool reports its fields missing, and the
     * malformed call then sits in the history poisoning every later request. Providers on this wire format include
     * {@code kimi-coding}, which is the default, so the default configuration was the one that hit it.
     *
     * <p>
     * The ceiling has to clear the thinking budget too, or the request is rejected outright: reasoning is drawn from
     * the same allowance, and at {@code --thinking max} that budget alone is 65536.
     */
    static int maxOutputTokens(ThinkingLevel thinkingLevel) {
        int thinking = thinkingLevel == null ? 0 : thinkingLevel.budgetTokens();
        // Enough for a real file in one call, and every model on this wire format in the catalogue accepts it.
        return Math.max(32_768, thinking + 8192);
    }

    record AnthropicProvider() implements ProviderFactory {
        @Override
        public StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                        ThinkingLevel thinkingLevel) {
            var builder = AnthropicStreamingChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                                            .version("2023-06-01").maxTokens(maxOutputTokens(thinkingLevel))
                                            .timeout(LlmTimeouts.request())
                                            .customHeaders(ProviderCatalog.headers(providerName))
                                            // cache the large static prefix (system prompt + tool schemas) re-sent
                                            // every
                                            // turn. Cache reads ≈ 10% of input cost. Other providers auto-cache this.
                                            .cacheSystemMessages(true).cacheTools(true);
            applyProxy(builder::httpClientBuilder);
            if (thinkingLevel != null && thinkingLevel != ThinkingLevel.OFF) {
                builder.thinkingType("enabled");
                builder.thinkingBudgetTokens(thinkingLevel.budgetTokens());
                // returnThinking defaults to false, so without this the turn is billed for reasoning tokens and the
                // thinking is discarded before it reaches the handler: no thinking block in the TUI, while /usage
                // still counts the tokens. The OpenAI-compatible provider above already sets it.
                builder.returnThinking(true);
            }
            return builder.build();
        }

        @Override
        public boolean supports(String p) {
            return "anthropic".equals(ProviderCatalog.apiType(p));
        }
    }

    static ProviderFactory resolve(String provider, List<ProviderFactory> customProviders) {
        for (var p : customProviders) {
            if (p.supports(provider))
                return p;
        }
        for (var p : builtins()) {
            if (p.supports(provider))
                return p;
        }
        throw new IllegalArgumentException("No LangChain4j provider for " + provider);
    }

    private static List<ProviderFactory> builtins() {
        var list = new ArrayList<ProviderFactory>();
        list.add(new OpenAiCompatible());
        try {
            list.add(new GoogleProvider());
        } catch (NoClassDefFoundError ignored) {}
        try {
            list.add(new AnthropicProvider());
        } catch (NoClassDefFoundError ignored) {}
        return list;
    }
}
