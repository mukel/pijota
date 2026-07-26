package org.pijota.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsManagerTest {

    private static SettingsManager managerWith(AppConfig config) {
        return new SettingsManager(config, c -> {});
    }

    @Test
    void constructionNeverPersistsSettings() {
        // Guards against the footgun where startup rewrote settings.json every launch (project_dir auto-save),
        // risking clobbering the user's file. project_dir is resolved in-memory; nothing is written on construction.
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        var sm = new SettingsManager(new AppConfig(), c -> saves.incrementAndGet()); // project_dir unset
        assertEquals(0, saves.get(), "constructing a SettingsManager must not write settings.json");
        assertNotNull(sm.projectDir(), "project_dir is still resolved on demand");
    }

    @Test
    void defaultConfigContextFilesEnabled() {
        var sm = managerWith(new AppConfig());
        assertTrue(sm.contextFilesEnabled());
    }

    @Test
    void setContextFilesEnabledPropagates() {
        var sm = managerWith(new AppConfig());
        sm.setContextFilesEnabled(false);
        assertFalse(sm.contextFilesEnabled());
        assertFalse(sm.config().contextFiles());
    }

    @Test
    void setThinkingLevel() {
        var sm = managerWith(new AppConfig());
        sm.setThinkingLevel("high");
        assertEquals("high", sm.config().thinkingLevel());
    }

    @Test
    void setHideThinking() {
        var sm = managerWith(new AppConfig());
        sm.setHideThinking(true);
        assertTrue(sm.config().hideThinking());
    }

    @Test
    void setQuietStartup() {
        var sm = managerWith(new AppConfig());
        sm.setQuietStartup(true);
        assertTrue(sm.config().quietStartup());
    }

    @Test
    void compactionEnabledDefaultTrue() {
        var sm = managerWith(new AppConfig());
        assertTrue(sm.compactionEnabled());
    }

    @Test
    void setCompactionEnabled() {
        var sm = managerWith(new AppConfig());
        sm.setCompactionEnabled(false);
        assertFalse(sm.compactionEnabled());
    }

    @Test
    void pathDisplayDefaultsToCompact() {
        var sm = managerWith(new AppConfig());
        assertEquals(PathDisplay.COMPACT, sm.pathDisplay());
    }

    @Test
    void maxTurnsDefaultsWhenUnset() {
        assertEquals(AppConfig.DEFAULT_MAX_TURNS, managerWith(new AppConfig()).maxTurns());
    }

    @Test
    void maxTurnsHonorsConfigIncludingZeroForUnlimited() {
        assertEquals(7, managerWith(new AppConfig().toBuilder().maxTurns(7).build()).maxTurns());
        assertEquals(0, managerWith(new AppConfig().toBuilder().maxTurns(0).build()).maxTurns()); // 0 = unlimited
    }

    @Test
    void setPathDisplayPersistsMode() {
        var sm = managerWith(new AppConfig());
        sm.setPathDisplay(PathDisplay.NAME);
        assertEquals(PathDisplay.NAME, sm.pathDisplay());
    }

    @Test
    void sessionDirDefault() {
        var sm = managerWith(new AppConfig());
        var dir = sm.sessionDir();
        assertTrue(dir.toString().contains(".pijota"));
    }

    @Test
    void sessionDirCustom() {
        var config = new AppConfig(null, null, null, null, null, null, "/custom/sessions", null, null, null, null, null,
                                        false, false, null, null, null, null, null, java.util.List.of(),
                                        java.util.Map.of(), null);
        var sm = managerWith(config);
        assertEquals(Path.of("/custom/sessions"), sm.sessionDir());
    }

    @Test
    void configHome() {
        var sm = managerWith(new AppConfig());
        assertTrue(sm.configHome().toString().endsWith(".pijota"));
    }

    @Test
    void requiresApiKeyForKnownProvider() {
        assertTrue(SettingsManager.requiresApiKey("openai"));
        assertTrue(SettingsManager.requiresApiKey("anthropic"));
    }

    @Test
    void requiresApiKeyForUnknownProviderReturnsFalse() {
        assertFalse(SettingsManager.requiresApiKey("nonexistent-provider"));
    }

    @Test
    void apiKeyHintReturnsEnvVarName() {
        var hint = managerWith(new AppConfig()).apiKeyHint("anthropic");
        assertTrue(hint.contains("API_KEY"));
    }

    @Test
    void effectiveShellPathUsesConfig() {
        var config = new AppConfig(null, null, null, null, "/bin/zsh", null, null, null, null, null, null, null, false,
                                        false, null, null, null, null, null, java.util.List.of(), java.util.Map.of(),
                                        null);
        var sm = managerWith(config);
        assertEquals("/bin/zsh", sm.effectiveShellPath());
    }

    @Test
    void effectiveShellPathFallbackToDefault() {
        var sm = managerWith(new AppConfig());
        assertNotNull(sm.effectiveShellPath());
        assertFalse(sm.effectiveShellPath().isBlank());
    }

    @Test
    void saveDoesNotThrowWithMockStore() {
        var recorded = new AppConfig[1];
        var sm = new SettingsManager(new AppConfig(), c -> recorded[0] = c);
        sm.save();
        assertNotNull(recorded[0]);
    }

    @Test
    void saveApiKeyUpdatesConfigAndCallsOnSave() throws Exception {
        var recorded = new AppConfig[1];
        var sm = new SettingsManager(new AppConfig(), c -> recorded[0] = c);
        sm.saveApiKey("openai", "sk-123", "gpt-4");
        assertNotNull(recorded[0]);
        assertEquals("sk-123", recorded[0].apiKeys().get("openai"));
    }

    @Test
    void removeApiKeyUpdatesConfigAndCallsOnSave() throws Exception {
        var config = new AppConfig().withApiKey("openai", "sk-123");
        var recorded = new AppConfig[1];
        var sm = new SettingsManager(config, c -> recorded[0] = c);
        sm.removeApiKey("openai");
        assertTrue(recorded[0].apiKeys().isEmpty() || !recorded[0].apiKeys().containsKey("openai"));
    }

    @Test
    void setDefaultModelForProviderCallsOnSave() throws Exception {
        var recorded = new AppConfig[1];
        var sm = new SettingsManager(new AppConfig(), c -> recorded[0] = c);
        sm.setDefaultModelForProvider("openai", "gpt-4o");
        assertNotNull(recorded[0]);
    }

    @Test
    void requiresApiKeyForOAuthProvider() {
        assertTrue(SettingsManager.requiresApiKey("openai-codex"));
    }

    @Test
    void resolveApiKeyReturnsEmptyForProviderWithoutKey() {
        var sm = managerWith(new AppConfig());
        assertEquals("", sm.resolveApiKey("local"));
    }

    @Test
    void resolveApiKeyReturnsStoredKey() {
        var sm = managerWith(new AppConfig().withApiKey("openai", "sk-stored"));
        assertEquals("sk-stored", sm.resolveApiKey("openai"));
    }

    @Test
    void resolveApiKeyReturnsNullWhenNoKeyAvailable() {
        var sm = managerWith(new AppConfig());
        assertNull(sm.resolveApiKey("openai"));
    }

    @Test
    void effectiveShellPathFallsBackToShellEnvironmentVariable() {
        var config = new AppConfig(null, null, null, null, null, null, null, null, null, null, null, null, false, false,
                                        null, null, null, null, null, java.util.List.of(), java.util.Map.of(), null);
        var sm = managerWith(config);
        String expected = System.getenv().getOrDefault("SHELL", "/bin/bash");
        assertEquals(expected, sm.effectiveShellPath());
    }

    @Test
    void apiKeyHintFallsBackToConventionForUnknownProvider() {
        var sm = managerWith(new AppConfig());
        assertEquals("MY_PROVIDER_API_KEY", sm.apiKeyHint("my-provider"));
    }

    @Test
    void projectDirUsesExplicitConfig() {
        var config = new AppConfig().withProjectDir("/explicit/repo");
        var sm = managerWith(config);
        assertEquals("/explicit/repo", sm.projectDir());
    }
}
