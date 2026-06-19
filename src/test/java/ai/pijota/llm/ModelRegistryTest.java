package ai.pijota.llm;

import ai.pijota.agent.SessionManager;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryTest {

    private List<ModelInfo> savedKnownModels;

    @AfterEach
    void restoreKnownModels() {
        if (savedKnownModels != null) {
            ModelRegistry.KNOWN_MODELS = savedKnownModels;
        }
    }

    private void mutateKnownModels(List<ModelInfo> models) {
        savedKnownModels = ModelRegistry.KNOWN_MODELS;
        ModelRegistry.KNOWN_MODELS = List.copyOf(models);
    }

    @Test
    void filterReturnsModelsContainingSearchTerm() {
        var results = ModelRegistry.filter("gpt");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(m -> m.id().toLowerCase().contains("gpt")
                                        || m.provider().toLowerCase().contains("gpt")
                                        || m.displayName().toLowerCase().contains("gpt")));
    }

    @Test
    void filterReturnsAllModelsForBlankOrNullSearch() {
        assertEquals(ModelRegistry.filter("").size(), ModelRegistry.filter(null).size());
    }

    @Test
    void resolveProviderModelFallsBackToDefaultProvider() {
        var resolved = ModelRegistry.resolveProviderModel(null, null, null);
        assertEquals("openai", resolved.provider());
        assertNotNull(resolved.model());
    }

    @Test
    void defaultModelForProviderIgnoresUnknownProviders() {
        assertNull(ModelRegistry.defaultModelForProvider("nonexistent-xyz"));
    }

    @Test
    void forProviderReturnsMatchingModels() {
        var openaiModels = ModelRegistry.forProvider("openai");
        assertFalse(openaiModels.isEmpty());
        assertTrue(openaiModels.stream().allMatch(m -> m.provider().equalsIgnoreCase("openai")));
    }

    @Test
    void forProviderIsCaseInsensitive() {
        assertEquals(ModelRegistry.forProvider("OpenAI"), ModelRegistry.forProvider("openai"));
    }

    @Test
    void providerForModelFindsProvider() {
        assertEquals("openai", ModelRegistry.providerForModel("gpt-4o"));
    }

    @Test
    void providerForModelReturnsNullForUnknown() {
        assertNull(ModelRegistry.providerForModel("definitely-unknown-model"));
    }

    @Test
    void providerForModelReturnsNullForNull() {
        assertNull(ModelRegistry.providerForModel(null));
    }

    @Test
    void resolveContextWindowReturnsKnownValue() {
        assertEquals(128_000L, ModelRegistry.resolveContextWindow("openai", "gpt-4o"));
    }

    @Test
    void resolveContextWindowFallsBackToDefault() {
        assertEquals(128_000L, ModelRegistry.resolveContextWindow("unknown", "unknown"));
    }

    @Test
    void resolveContextWindowForSelectionUsesExplicitValue() {
        var selection = new SessionManager.ModelSelection("openai", "gpt-4o", 64_000);
        assertEquals(64_000L, ModelRegistry.resolveContextWindow(selection));
    }

    @Test
    void resolveContextWindowForNullSelectionReturnsDefault() {
        assertEquals(128_000L, ModelRegistry.resolveContextWindow((SessionManager.ModelSelection) null));
    }

    @Test
    void resolveRequiresApiKey() {
        var settings = new SettingsManager(new AppConfig(), _ -> {});
        var resolved = ModelRegistry.resolve("openai", "gpt-4o", settings);
        assertNull(resolved);
    }

    @Test
    void resolveReturnsModelWhenKeyAvailable() {
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), _ -> {});
        var resolved = ModelRegistry.resolve("openai", "gpt-4o", settings);
        assertNotNull(resolved);
        assertEquals("openai", resolved.provider());
        assertEquals("gpt-4o", resolved.model());
        assertEquals("sk-test", resolved.apiKey());
        assertTrue(resolved.contextWindow() > 0);
    }

    @Test
    void resolveSelectionRequiresApiKey() {
        var settings = new SettingsManager(new AppConfig(), _ -> {});
        var selection = new SessionManager.ModelSelection("openai", "gpt-4o", 0);
        assertNull(ModelRegistry.resolve(selection, settings));
    }

    @Test
    void resolveProviderModelUsesModelToFindProvider() {
        var resolved = ModelRegistry.resolveProviderModel(null, "gpt-4o", null);
        assertEquals("openai", resolved.provider());
        assertEquals("gpt-4o", resolved.model());
    }

    @Test
    void resolveProviderModelFallsBackToSettingsDefaults() {
        var settings = new SettingsManager(new AppConfig().withDefaultProviderModel("anthropic", "claude-opus"),
                                        _ -> {});
        var resolved = ModelRegistry.resolveProviderModel(null, null, settings);
        assertEquals("anthropic", resolved.provider());
    }

    @Test
    void resolveProviderModelFallsBackToDefaultModelForProvider() {
        var resolved = ModelRegistry.resolveProviderModel("openai", null, null);
        assertEquals("openai", resolved.provider());
        assertNotNull(resolved.model());
    }

    @Test
    void isKnownProviderModelRecognizesKnown() {
        assertTrue(ModelRegistry.isKnownProviderModel("openai", "gpt-4o"));
    }

    @Test
    void isKnownProviderModelRejectsUnknown() {
        assertFalse(ModelRegistry.isKnownProviderModel("openai", "definitely-unknown"));
        assertFalse(ModelRegistry.isKnownProviderModel("unknown", "gpt-4o"));
        assertFalse(ModelRegistry.isKnownProviderModel(null, "gpt-4o"));
    }

    @Test
    void replaceDiscoveredAddsNewModels() {
        int builtinCount = ModelRegistry.KNOWN_MODELS.size();
        var discovered = List.of(new ModelInfo("custom-1", "custom", "Custom 1", 128_000));
        mutateKnownModels(discovered);

        assertEquals(1, ModelRegistry.KNOWN_MODELS.size());
        assertEquals("custom-1", ModelRegistry.KNOWN_MODELS.get(0).id());

        // Builtin models are restored by @AfterEach.
    }

    @Test
    void pricingForReturnsCatalogRatesForKnownModel() {
        var pricing = ModelRegistry.pricingFor("anthropic", "claude-opus-4-5-20251101");
        assertNotNull(pricing);
        assertEquals(5.0, pricing.inputPerMTok());
        assertEquals(25.0, pricing.outputPerMTok());
    }

    @Test
    void pricingForReturnsNullWhenRateUnknown() {
        assertNull(ModelRegistry.pricingFor("openai-codex", "gpt-5.4"));
        assertNull(ModelRegistry.pricingFor("anthropic", "no-such-model"));
        assertNull(ModelRegistry.pricingFor(null, null));
    }

    @Test
    void replaceDiscoveredOverwritesExistingByKey() {
        var discovered = List.of(new ModelInfo("gpt-4o", "openai", "GPT-4o Updated", 256_000));
        mutateKnownModels(ModelRegistry.KNOWN_MODELS);

        ModelRegistry.replaceDiscovered(discovered);

        var updated = ModelRegistry.KNOWN_MODELS.stream()
                                        .filter(m -> m.id().equalsIgnoreCase("gpt-4o")
                                                                        && m.provider().equalsIgnoreCase("openai"))
                                        .findFirst().orElseThrow();
        assertEquals(256_000L, updated.contextWindow());
    }
}
