package org.pijota.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

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
    void saveRestrictsSettingsToOwnerOnly() throws IOException {
        // settings.json holds API keys — it (and the config dir) must not be readable by other local users.
        var fs = FileSystems.getDefault();
        Assumptions.assumeTrue(fs.supportedFileAttributeViews().contains("posix"));
        Path dir = Files.createTempDirectory("pijota-sec");
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
