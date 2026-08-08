package org.pijota.llm;

import org.pijota.agent.ModelSelection;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.util.Util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryTest {

    @Test
    void closestModelSuggestsTheNearestCatalogueEntryForATypo() {
        // the reported bug: `-m deepseek/deepseek-v4-fast` → should nudge toward the real `deepseek-v4-flash`
        assertEquals("deepseek-v4-flash", ModelRegistry.closestModel("deepseek", "deepseek-v4-fast").orElseThrow());
    }

    @Test
    void closestModelReturnsEmptyForAGenuinelyUnknownModel() {
        // far from anything catalogued → no misleading suggestion (new/uncatalogued models pass through silently)
        assertTrue(ModelRegistry.closestModel("deepseek", "totally-different-xyz-9000").isEmpty());
        assertTrue(ModelRegistry.closestModel("deepseek", null).isEmpty());
        assertTrue(ModelRegistry.closestModel(null, "x").isEmpty());
    }

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
    void inputModalitiesReflectCatalogAndAreLenientForUnknownModels() {
        mutateKnownModels(List.of(new ModelInfo("vis", "p", "Vision", 1000, null, null, List.of("text", "image")),
                                        new ModelInfo("txt", "p", "Text", 1000, null, null, List.of("text"))));
        assertTrue(ModelRegistry.supports("p", "vis", Modality.IMAGE), "catalog model declares image");
        assertFalse(ModelRegistry.supports("p", "txt", Modality.IMAGE), "text-only catalog model rejects image");
        assertTrue(ModelRegistry.supports("p", "txt", Modality.TEXT), "text is always accepted");
        assertTrue(ModelRegistry.supports("p", "brand-new-model", Modality.IMAGE), "unknown model → not blocked");
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
    void availableModelsNeedANonBlankKeyAndDefaultToTheCatalogueWithoutSettings() {
        assertEquals(ModelRegistry.KNOWN_MODELS, ModelRegistry.availableModels(null),
                                        "settings unknown → unfiltered catalogue (env-independent)");

        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        var models = ModelRegistry.availableModels(settings);
        assertFalse(models.isEmpty(), "an openai key set → at least the openai builtins are offered");
        for (var m : models) { // env-independent invariant: never offer a model whose provider has no usable key
            String key = settings.resolveApiKey(m.provider());
            assertTrue(key != null && !key.isBlank(), "offered a model with no key for " + m.provider());
        }
    }

    @Test
    void searchNarrowsAGivenListWithoutReachingIntoTheCatalogue() {
        // The composition --list-models uses: usable models (by key), then a text filter, never the full catalogue.
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        var usable = ModelRegistry.availableModels(settings);
        var matches = ModelRegistry.search(usable, "gpt");

        assertFalse(matches.isEmpty());
        assertTrue(usable.containsAll(matches), "search must not add models from outside the usable set");
        for (var m : matches)
            assertTrue((m.id() + m.provider() + m.displayName()).toLowerCase().contains("gpt"));
    }

    @Test
    void byIdMatchesCaseInsensitivelyAndIsEmptyForUnknownOrNull() {
        var any = ModelRegistry.KNOWN_MODELS.get(0).id();
        assertTrue(ModelRegistry.byId(any).isPresent());
        assertTrue(ModelRegistry.byId(any.toUpperCase()).isPresent(), "lookup is case-insensitive");
        assertTrue(ModelRegistry.byId("no-such-model-xyz").isEmpty());
        assertTrue(ModelRegistry.byId(null).isEmpty());
    }

    @Test
    void resolveProviderModelFallsBackToDefaultProvider() {
        var resolved = ModelRegistry.resolveProviderModel(null, null, null);
        assertEquals("openai", resolved.provider());
        assertNotNull(resolved.model());
    }

    /**
     * The settings default model belongs to the settings default provider. Naming a provider and no model used to keep
     * that model anyway, so `--provider anthropic` with deepseek configured as the default sent a deepseek model id to
     * Anthropic, which answered about a model the user never mentioned.
     */
    @Test
    void namingAProviderWithNoModelUsesThatProvidersDefault() {
        var settings = new SettingsManager(new AppConfig().withDefaultProviderModel("deepseek", "deepseek-v4-flash"),
                                        ignored -> {});

        var resolved = ModelRegistry.resolveProviderModel("anthropic", null, settings);

        assertEquals("anthropic", resolved.provider());
        assertNotEquals("deepseek-v4-flash", resolved.model(), "that model is not Anthropic's to serve");
        assertTrue(ModelRegistry.isKnownProviderModel("anthropic", resolved.model()),
                                        "and the replacement has to be one this provider actually serves: "
                                                                        + resolved.model());
    }

    /** An explicitly named model is still honoured verbatim, off-catalog ids included. */
    @Test
    void namingBothKeepsTheModelEvenWhenItIsOffCatalogue() {
        var settings = new SettingsManager(new AppConfig().withDefaultProviderModel("deepseek", "deepseek-v4-flash"),
                                        ignored -> {});

        var resolved = ModelRegistry.resolveProviderModel("anthropic", "some-local-finetune", settings);

        assertEquals("some-local-finetune", resolved.model());
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
        assertEquals("openai", ModelRegistry.providerForModel("gpt-5.5"));
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
        var selection = new ModelSelection("openai", "gpt-4o", 64_000);
        assertEquals(64_000L, ModelRegistry.resolveContextWindow(selection));
    }

    @Test
    void resolveContextWindowForNullSelectionReturnsDefault() {
        assertEquals(128_000L, ModelRegistry.resolveContextWindow((ModelSelection) null));
    }

    @Test
    void resolveRequiresApiKey() {
        var settings = new SettingsManager(new AppConfig(), ignored -> {});
        var resolved = ModelRegistry.resolve("openai", "gpt-5.5", settings);
        assertNull(resolved);
    }

    @Test
    void resolveReturnsModelWhenKeyAvailable() {
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), ignored -> {});
        var resolved = ModelRegistry.resolve("openai", "gpt-5.5", settings);
        assertNotNull(resolved);
        assertEquals("openai", resolved.provider());
        assertEquals("gpt-5.5", resolved.model());
        assertEquals("sk-test", resolved.apiKey());
        assertTrue(resolved.contextWindow() > 0);
    }

    @Test
    void resolveSelectionRequiresApiKey() {
        var settings = new SettingsManager(new AppConfig(), ignored -> {});
        var selection = new ModelSelection("openai", "gpt-4o", 0);
        assertNull(ModelRegistry.resolve(selection, settings));
    }

    @Test
    void resolveProviderModelUsesModelToFindProvider() {
        var resolved = ModelRegistry.resolveProviderModel(null, "gpt-5.5", null);
        assertEquals("openai", resolved.provider());
        assertEquals("gpt-5.5", resolved.model());
    }

    @Test
    void resolveProviderModelFallsBackToSettingsDefaults() {
        var settings = new SettingsManager(new AppConfig().withDefaultProviderModel("anthropic", "claude-opus"),
                                        ignored -> {});
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
    void resolvesModelAliasToConfiguredTarget() throws Exception {
        var config = Util.MAPPER.readValue("{\"aliases\":{\"fast\":\"anthropic/claude-haiku-4-5\"}}", AppConfig.class);
        var settings = new SettingsManager(config, ignored -> {});
        var resolved = ModelRegistry.resolveProviderModel("alias", "fast", settings);
        assertEquals("anthropic", resolved.provider());
        assertEquals("claude-haiku-4-5", resolved.model());
        assertEquals("anthropic", ModelRegistry.resolveAlias("fast", settings).provider());
    }

    @Test
    void unknownAliasResolvesToNull() {
        var settings = new SettingsManager(new AppConfig(), ignored -> {});
        assertNull(ModelRegistry.resolveAlias("nope", settings));
    }

    @Test
    void isKnownProviderModelRecognizesKnown() {
        assertTrue(ModelRegistry.isKnownProviderModel("openai", "gpt-5.5"));
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
    void pricingForCarriesLongContextTiers() {
        // grok-4.3 charges its base rate up to 200K input, then a higher rate for the whole request.
        var pricing = ModelRegistry.pricingFor("xai", "grok-4.3");
        assertNotNull(pricing);
        assertEquals(1.25, pricing.ratesFor(100_000).inputPerMTok(), "base rate below the tier");
        assertEquals(2.5, pricing.ratesFor(100_000).outputPerMTok());
        assertEquals(2.5, pricing.ratesFor(250_000).inputPerMTok(), "tier rate above 200K");
        assertEquals(5.0, pricing.ratesFor(250_000).outputPerMTok());
    }

    @Test
    void ratesForPicksHighestExceededTier() {
        var pricing = new ModelRegistry.Pricing(1, 2, List.of(new ModelRegistry.Pricing.Tier(1000, 3, 4),
                                        new ModelRegistry.Pricing.Tier(5000, 7, 8)));
        assertEquals(1, pricing.ratesFor(500).inputPerMTok(), "below all tiers: base");
        assertEquals(3, pricing.ratesFor(2000).inputPerMTok(), "between tiers: lower tier");
        assertEquals(7, pricing.ratesFor(9000).inputPerMTok(), "above all tiers: highest tier");
    }

    /**
     * Discovery adds; it does not overwrite. A provider's /v1/models returns little more than ids, so a discovered
     * record has no pricing and a placeholder window, and letting it win replaced curated entries with poorer ones.
     * This used to assert the opposite, and passed only because gpt-4o is not in the bundled catalog.
     */
    @Test
    void replaceDiscoveredNeverDowngradesAModelTheCatalogAlreadyHas() {
        var builtin = ModelRegistry.KNOWN_MODELS.stream().filter(m -> m.inputCostPerMTok() != null).findFirst()
                                        .orElseThrow();
        mutateKnownModels(ModelRegistry.KNOWN_MODELS);

        ModelRegistry.replaceDiscovered(List
                                        .of(new ModelInfo(builtin.id(), builtin.provider(), "Rediscovered", 1_000)));

        var after = ModelRegistry.byId(builtin.id()).orElseThrow();
        assertEquals(builtin.contextWindow(), after.contextWindow(), "the curated window survives");
        assertEquals(builtin.inputCostPerMTok(), after.inputCostPerMTok(), "and so does the price");
    }

    /** What discovery is for: the models the catalog has never heard of. */
    @Test
    void replaceDiscoveredAddsAModelTheCatalogDoesNotHave() {
        mutateKnownModels(ModelRegistry.KNOWN_MODELS);

        ModelRegistry.replaceDiscovered(List.of(new ModelInfo("only-on-my-box", "openai", "Local", 256_000)));

        assertEquals(256_000L, ModelRegistry.byId("only-on-my-box").orElseThrow().contextWindow());
    }

    @Test
    void isEnabledMatchesAllowlistGlobs() {
        var haiku = new ModelInfo("claude-haiku-4-5", "anthropic", "Haiku", 200_000);
        var gpt = new ModelInfo("gpt-5.5", "openai", "GPT", 400_000);

        assertTrue(ModelRegistry.isEnabled(haiku, null), "null patterns = all allowed");
        assertTrue(ModelRegistry.isEnabled(haiku, List.of()), "empty patterns = all allowed");
        assertTrue(ModelRegistry.isEnabled(haiku, List.of("anthropic/*")));
        assertFalse(ModelRegistry.isEnabled(gpt, List.of("anthropic/*")));
        assertTrue(ModelRegistry.isEnabled(haiku, List.of("*haiku*")), "glob on the bare id");
        assertTrue(ModelRegistry.isEnabled(gpt, List.of("anthropic/*", "gpt-5.5")), "exact id in the list");
        assertFalse(ModelRegistry.isEnabled(gpt, List.of("*claude*")));
    }

    @Test
    void applyAllowlistFiltersFailsOpenAndPassesThroughWhenUnset() {
        var haiku = new ModelInfo("claude-haiku-4-5", "anthropic", "Haiku", 200_000);
        var gpt = new ModelInfo("gpt-5.5", "openai", "GPT", 400_000);
        var all = List.of(haiku, gpt);

        assertEquals(all, ModelRegistry.applyAllowlist(all, null), "null patterns: unchanged");
        assertEquals(all, ModelRegistry.applyAllowlist(all, List.of()), "empty patterns: unchanged");
        assertEquals(List.of(haiku), ModelRegistry.applyAllowlist(all, List.of("anthropic/*")), "filters to matches");
        assertEquals(all, ModelRegistry.applyAllowlist(all, List.of("matches/nothing")),
                                        "fails open when the allowlist would empty the list");
    }
}
