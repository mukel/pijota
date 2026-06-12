package ai.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryTest {
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
}
