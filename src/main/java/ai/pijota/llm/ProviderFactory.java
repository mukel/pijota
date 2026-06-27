package ai.pijota.llm;

import java.util.ArrayList;

import ai.pijota.config.ProviderCatalog;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface ProviderFactory {

    StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                    ThinkingLevel thinkingLevel);

    default boolean supports(String provider) {
        return false;
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
            // Generous request timeout: local models (and long agentic generations) routinely exceed 60s; this
            // matches the budget the former hand-rolled local client used and the future's completion timeout.
            var builder = OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                                            .timeout(Duration.ofMinutes(5));
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
            return dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel.builder().apiKey(apiKey)
                                            .modelName(modelName).build();
        }

        @Override
        public boolean supports(String p) {
            String apiType = ProviderCatalog.apiType(p);
            return apiType == null ? "google".equals(p) : "google".equals(apiType);
        }
    }

    record AnthropicProvider() implements ProviderFactory {
        @Override
        public StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                        ThinkingLevel thinkingLevel) {
            var builder = dev.langchain4j.model.anthropic.AnthropicStreamingChatModel.builder().baseUrl(baseUrl)
                                            .apiKey(apiKey).modelName(modelName).version("2023-06-01").maxTokens(4096)
                                            .customHeaders(ProviderCatalog.headers(providerName))
                                            // cache the large static prefix (system prompt + tool schemas) re-sent
                                            // every
                                            // turn — cache reads ≈ 10% of input cost. Other providers auto-cache this.
                                            .cacheSystemMessages(true).cacheTools(true);
            if (thinkingLevel != null && thinkingLevel != ThinkingLevel.OFF) {
                builder.thinkingType("enabled");
                builder.thinkingBudgetTokens(thinkingLevel.budgetTokens());
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
