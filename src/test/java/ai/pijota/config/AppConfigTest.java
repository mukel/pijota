package ai.pijota.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void defaultConstructorSetsDefaults() {
        var config = new AppConfig();
        assertNull(config.defaultProvider());
        assertNull(config.defaultModel());
        assertTrue(config.apiKeys().isEmpty());
        assertEquals("medium", config.thinkingLevel());
        assertEquals(3, config.maxRetries());
        assertEquals(2000L, config.retryBaseDelayMs());
        assertNotNull(config.compaction());
        assertTrue(config.contextFiles());
        assertFalse(config.hideThinking());
    }

    @Test
    void withApiKeyAddsKey() {
        var config = new AppConfig();
        var updated = config.withApiKey("openai", "sk-123");
        assertEquals("sk-123", updated.apiKeys().get("openai"));
        assertEquals("openai", updated.defaultProvider());
    }

    @Test
    void withApiKeyPreservesExistingKeys() {
        var config = new AppConfig().withApiKey("openai", "sk-123");
        var updated = config.withApiKey("google", "gk-456");
        assertTrue(updated.apiKeys().containsKey("openai"));
        assertTrue(updated.apiKeys().containsKey("google"));
    }

    @Test
    void withoutApiKeyRemovesKey() {
        var config = new AppConfig().withApiKey("openai", "sk-123");
        var updated = config.withoutApiKey("openai");
        assertFalse(updated.apiKeys().containsKey("openai"));
    }

    @Test
    void withoutApiKeyPreservesOtherKeys() {
        var config = new AppConfig().withApiKey("openai", "sk-1").withApiKey("google", "gk-2");
        var updated = config.withoutApiKey("openai");
        assertTrue(updated.apiKeys().containsKey("google"));
        assertFalse(updated.apiKeys().containsKey("openai"));
    }

    @Test
    void defaultCompactionConfigHasDefaults() {
        var config = new AppConfig();
        var compaction = config.compaction();
        assertNotNull(compaction);
        assertTrue(compaction.enabled());
    }

    @Test
    void themesDefaultsToListOf() {
        var config = new AppConfig();
        assertNotNull(config.themes());
        assertTrue(config.themes().isEmpty());
    }

    @Test
    void enabledModelsDefaultsToListOf() {
        var config = new AppConfig();
        assertNotNull(config.enabledModels());
        assertTrue(config.enabledModels().isEmpty());
    }

    @Test
    void allBooleanDefaults() {
        var config = new AppConfig();
        assertTrue(config.contextFiles());
        assertFalse(config.hideThinking());
        assertFalse(config.quietStartup());
        assertFalse(config.shortenPaths());
    }
}
