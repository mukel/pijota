package ai.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFactoryTest {

    @Test
    void openAiCompatibleSupportsOpenAiProviders() {
        var factory = new ProviderFactory.OpenAiCompatible();
        assertTrue(factory.supports("deepseek"));
        assertTrue(factory.supports("openai"));
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
}
