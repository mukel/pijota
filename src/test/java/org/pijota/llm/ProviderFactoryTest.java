package org.pijota.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFactoryTest {

    @Test
    void createBuildsAStreamingModelForEachProvider() {
        // model construction only (no network); covers the builder paths incl. the thinking-enabled branch
        assertNotNull(new ProviderFactory.OpenAiCompatible().create("https://api.openai.com/v1", "sk", "gpt-4",
                                        "openai", ThinkingLevel.OFF));
        assertNotNull(new ProviderFactory.OpenAiCompatible().create("https://api.openai.com/v1", "sk", "gpt-4",
                                        "openai", ThinkingLevel.HIGH));
        assertNotNull(new ProviderFactory.GoogleProvider().create(null, "key", "gemini-2.0", "google",
                                        ThinkingLevel.OFF));
        assertNotNull(new ProviderFactory.AnthropicProvider().create("https://api.anthropic.com", "sk", "claude-x",
                                        "anthropic", ThinkingLevel.OFF));
        assertNotNull(new ProviderFactory.AnthropicProvider().create("https://api.anthropic.com", "sk", "claude-x",
                                        "anthropic", ThinkingLevel.HIGH)); // thinkingType/budget branch
    }

    @Test
    void buildsProvidersWithAProxyConfigured() {
        // With a proxy set, OpenAI/Anthropic get a proxied httpClientBuilder injected; construction must still succeed.
        System.setProperty("pijota.proxy", "http://proxy.example:8080");
        try {
            assertNotNull(new ProviderFactory.OpenAiCompatible().create("https://api.openai.com/v1", "sk", "gpt-4",
                                            "openai", ThinkingLevel.OFF));
            assertNotNull(new ProviderFactory.AnthropicProvider().create("https://api.anthropic.com", "sk", "claude-x",
                                            "anthropic", ThinkingLevel.OFF));
            assertInstanceOf(CodexStreamingChatModel.class, new ProviderFactory.OpenAiCompatible().create(
                                            "https://chatgpt.com/backend-api", "sk", "gpt-5-codex", "openai-codex",
                                            ThinkingLevel.OFF));
        } finally {
            System.clearProperty("pijota.proxy");
        }
    }

    @Test
    void openAiCompatibleUsesTheCodexModelForTheOpenAiCodexProvider() {
        var model = new ProviderFactory.OpenAiCompatible().create("https://chatgpt.com/backend-api", "sk",
                                        "gpt-5-codex", "openai-codex", ThinkingLevel.OFF);
        assertInstanceOf(CodexStreamingChatModel.class, model);
    }

    @Test
    void openAiCompatibleSupportsOpenAiProviders() {
        var factory = new ProviderFactory.OpenAiCompatible();
        assertTrue(factory.supports("deepseek"));
        assertTrue(factory.supports("openai"));
    }

    @Test
    void openAiCompatibleProvidersAreSupported() {
        // apiType=openai providers route to the standard OpenAI-compatible client
        var factory = new ProviderFactory.OpenAiCompatible();
        assertTrue(factory.supports("deepseek"));
        assertTrue(factory.supports("groq"));
    }

    @Test
    void thinkingParametersMapByFormat() {
        assertEquals(Map.of(), ProviderFactory.thinkingParameters(null, true), "standard OpenAI sends no extra params");
        assertEquals(Map.of("enable_thinking", true), ProviderFactory.thinkingParameters("qwen", true));
        assertEquals(Map.of("enable_thinking", false), ProviderFactory.thinkingParameters("qwen", false));
        assertEquals(Map.of("enable_thinking", true, "chat_template_kwargs", Map.of("enable_thinking", true)),
                                        ProviderFactory.thinkingParameters("qwen-chat-template", true));
        assertEquals(Map.of(), ProviderFactory.thinkingParameters("unknown-format", true), "unknown format → none");
    }

    @Test
    void openAiCompatibleDoesNotSupportAnthropic() {
        var factory = new ProviderFactory.OpenAiCompatible();
        assertFalse(factory.supports("anthropic"));
    }

    @Test
    void openAiCompatibleDoesNotSupportGoogle() {
        var factory = new ProviderFactory.OpenAiCompatible();
        assertFalse(factory.supports("google"));
    }

    @Test
    void openAiCompatibleSupportsUnknownByDefault() {
        var factory = new ProviderFactory.OpenAiCompatible();
        assertTrue(factory.supports("unknown-provider"));
    }

    @Test
    void googleProviderSupportsGoogle() {
        var factory = new ProviderFactory.GoogleProvider();
        assertTrue(factory.supports("google"));
    }

    @Test
    void googleProviderDoesNotSupportAnthropic() {
        var factory = new ProviderFactory.GoogleProvider();
        assertFalse(factory.supports("anthropic"));
    }

    @Test
    void googleProviderSupportsUnknownProviderWithDefault() {
        var factory = new ProviderFactory.GoogleProvider();
        assertFalse(factory.supports("unknown"));
    }

    @Test
    void anthropicProviderSupportsAnthropic() {
        var factory = new ProviderFactory.AnthropicProvider();
        assertTrue(factory.supports("anthropic"));
    }

    @Test
    void anthropicProviderDoesNotSupportOpenAi() {
        var factory = new ProviderFactory.AnthropicProvider();
        assertFalse(factory.supports("openai"));
    }

    @Test
    void anthropicProviderDoesNotSupportGoogle() {
        var factory = new ProviderFactory.AnthropicProvider();
        assertFalse(factory.supports("google"));
    }

    /**
     * The Anthropic wire format requires an output ceiling and does not default one. It was pinned at 4096, low enough
     * that writing a page of HTML ran out of room mid-tool-call, and that truncation left unterminated JSON that
     * poisoned every later request. kimi-coding is on this wire format and is the default provider.
     */
    @Test
    void theOutputCeilingLeavesRoomToWriteARealFile() {
        assertTrue(ProviderFactory.maxOutputTokens(null) >= 32_768,
                                        "4096 truncates a page of HTML partway through the tool call");
        assertTrue(ProviderFactory.maxOutputTokens(ThinkingLevel.OFF) >= 32_768);
    }

    /**
     * Reasoning is drawn from the same allowance, so a ceiling at or below the thinking budget is rejected outright. At
     * --thinking max the budget alone is 65536, well past any fixed 32K.
     */
    @Test
    void theCeilingAlwaysClearsTheThinkingBudget() {
        for (var level : ThinkingLevel.values())
            assertTrue(ProviderFactory.maxOutputTokens(level) > level.budgetTokens(),
                                            level + ": ceiling " + ProviderFactory.maxOutputTokens(level)
                                                                            + " must exceed its own thinking budget "
                                                                            + level.budgetTokens());
    }
}
