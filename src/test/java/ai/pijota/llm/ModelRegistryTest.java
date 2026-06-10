package ai.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryTest {
    @Test
    void providerForModelResolvesBuiltinModels() {
        assertNotNull(ModelRegistry.providerForModel("gpt-4.1"));
    }

    @Test
    void defaultModelForProviderReturnsKnownModel() {
        var model = ModelRegistry.defaultModelForProvider("openai");
        assertNotNull(model);
        assertFalse(model.isBlank());
    }

    @Test
    void resolveProviderModelFallsBackToOpenaiWhenProviderIsNull() {
        var result = ModelRegistry.resolveProviderModel(null, null, null);

        assertEquals("openai", result.provider());
        assertNotNull(result.model());
    }

    @Test
    void resolveContextWindowReturnsPositiveValue() {
        var window = ModelRegistry.resolveContextWindow("openai", "gpt-4.1");

        assertTrue(window > 0);
    }

    @Test
    void resolveProviderModelPreservesExplicitProvider() {
        var result = ModelRegistry.resolveProviderModel("anthropic", "claude-sonnet-4-20250514", null);

        assertEquals("anthropic", result.provider());
        assertTrue(result.model().contains("claude-sonnet-4"));
    }
}
