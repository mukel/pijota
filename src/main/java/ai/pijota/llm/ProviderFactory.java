package ai.pijota.llm;

import java.util.ArrayList;

import ai.pijota.config.ProviderCatalog;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.List;

public interface ProviderFactory {

    StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                    ThinkingLevel thinkingLevel);

    default boolean supports(String provider) {
        return false;
    }

    record OpenAiCompatible() implements ProviderFactory {
        @Override
        public StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                        ThinkingLevel thinkingLevel) {
            if ("openai-codex".equals(providerName)) {
                return new CodexStreamingChatModel(baseUrl, apiKey, modelName, providerName, thinkingLevel);
            }
            if ("local".equals(providerName) || "workstation".equals(providerName)) {
                return new LocalOpenAiStreamingChatModel(baseUrl, apiKey, modelName, thinkingLevel);
            }
            var builder = OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                                            .timeout(Duration.ofSeconds(60));
            if (thinkingLevel != null && thinkingLevel != ThinkingLevel.OFF) {
                builder.returnThinking(true);
            }
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
                                            .customHeaders(ProviderCatalog.headers(providerName));
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
