package org.pijota.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    /**
     * A settings file holding literal {@code null} parses to a null object rather than throwing, so it slipped past the
     * caller's IOException fallback and NPE'd startup with "Pijota failed" and no way back. Every other malformation --
     * truncated, wrong types, an array where an object belongs -- already degraded to defaults.
     */
    @Test
    void aNullSettingsFileLoadsAsDefaultsRatherThanCrashing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "null");

        var config = AppConfig.load(file);

        assertNotNull(config, "a null settings file bricked startup");
    }

    @Test
    void defaultConstructorSetsDefaults() {
        var config = new AppConfig();
        assertNull(config.defaultProvider());
        assertNull(config.defaultModel());
        assertTrue(config.apiKeys().isEmpty());
        assertEquals("medium", config.thinkingLevel());
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
        assertEquals(0, compaction.triggerTokens(), "no absolute auto-compact trigger by default");
    }

    @Test
    void compactionTriggerTokensSurvivesAJsonRoundTrip() throws Exception {
        var mapper = org.pijota.util.Util.MAPPER;
        var withTrigger = new AppConfig.CompactionConfig(true, 16384, 20000, 100000);
        var read = mapper.readValue(mapper.writeValueAsString(withTrigger), AppConfig.CompactionConfig.class);
        assertEquals(100000, read.triggerTokens());
        // A config written before the field existed (no trigger_tokens key) must still load, defaulting to 0.
        var legacy = mapper.readValue("{\"enabled\":true,\"reserve_tokens\":16384,\"keep_recent_tokens\":20000}",
                                        AppConfig.CompactionConfig.class);
        assertEquals(0, legacy.triggerTokens());
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
        assertNull(config.pathDisplay()); // unset by default → SettingsManager resolves it to COMPACT
    }

    @Test
    void loadMissingFileReturnsDefaultConfig() throws IOException {
        Path missing = Files.createTempFile("missing", ".json");
        Files.deleteIfExists(missing);
        var config = AppConfig.load(missing);
        assertEquals("medium", config.thinkingLevel());
        assertTrue(config.apiKeys().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        Path file = Files.createTempFile("config", ".json");
        var original = new AppConfig().withApiKey("openai", "sk-123").withThinkingLevel("high");
        original.save(file);
        var loaded = AppConfig.load(file);
        assertEquals("sk-123", loaded.apiKeys().get("openai"));
        assertEquals("high", loaded.thinkingLevel());
        Files.deleteIfExists(file);
    }

    @Test
    void saveAndLoadRoundTripPreservesProvidersAndPrompt() throws IOException {
        // The exact graph that crashed the native image: saving a config with custom providers + a prompt reflectively
        // invokes the ProviderCatalog.Provider / PromptGlyphs accessors, which must be registered in
        // reachability-metadata.json. On the JVM this always works; the test documents the round-trip and guards it.
        Path file = Files.createTempFile("config", ".json");
        var provider = new ProviderCatalog.Provider("myco", "MyCo", "openai", "https://api.myco.test",
                                        List.of("MYCO_API_KEY"), "myco-1", Map.of(), null, null);
        var original = new AppConfig().toBuilder().providers(List.of(provider)).prompt(new PromptGlyphs()).build();
        original.save(file);
        var loaded = AppConfig.load(file);
        assertEquals("myco", loaded.providers().get(0).id());
        assertEquals("https://api.myco.test", loaded.providers().get(0).baseUrl());
        assertNotNull(loaded.prompt());
        Files.deleteIfExists(file);
    }

    @Test
    void saveRestrictsSettingsToOwnerOnly(@TempDir Path dir) throws IOException {
        // settings.json holds API keys, so it (and the config dir) must not be readable by other local users.
        var fs = FileSystems.getDefault();
        Assumptions.assumeTrue(fs.supportedFileAttributeViews().contains("posix"));
        Path file = dir.resolve("nested").resolve("settings.json");
        new AppConfig().withApiKey("openai", "sk-secret").save(file);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
    }

    @Test
    void loadReadsJsonValues() throws IOException {
        Path file = Files.createTempFile("config", ".json");
        Files.writeString(file, """
                                        {
                                          "default_provider": "anthropic",
                                          "default_model": "claude-sonnet",
                                          "thinking_level": "low",
                                          "hide_thinking": true
                                        }
                                        """);
        var loaded = AppConfig.load(file);
        assertEquals("anthropic", loaded.defaultProvider());
        assertEquals("claude-sonnet", loaded.defaultModel());
        assertEquals("low", loaded.thinkingLevel());
        assertTrue(loaded.hideThinking());
        Files.deleteIfExists(file);
    }

    @Test
    void withDefaultProviderModelUpdatesBothFields() {
        var config = new AppConfig().withDefaultProviderModel("google", "gemini-2");
        assertEquals("google", config.defaultProvider());
        assertEquals("gemini-2", config.defaultModel());
    }

    @Test
    void withContextFilesTogglesFlag() {
        var config = new AppConfig().withContextFiles(false);
        assertFalse(config.contextFiles());
    }

    @Test
    void withHideThinkingTogglesFlag() {
        var config = new AppConfig().withHideThinking(true);
        assertTrue(config.hideThinking());
    }

    @Test
    void withQuietStartupTogglesFlag() {
        var config = new AppConfig().withQuietStartup(true);
        assertTrue(config.quietStartup());
    }

    @Test
    void withPathDisplaySetsMode() {
        var config = new AppConfig().withPathDisplay("name");
        assertEquals("name", config.pathDisplay());
    }

    @Test
    void withCompactionEnabledTogglesFlag() {
        var config = new AppConfig().withCompactionEnabled(false);
        assertNotNull(config.compaction());
        assertFalse(config.compaction().enabled());
    }
}
