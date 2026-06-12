package ai.pijota.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsManagerTest {

    private static SettingsManager managerWith(AppConfig config) {
        return new SettingsManager(config, c -> {});
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
    void shortenPathsEnabledDefaultFalse() {
        var sm = managerWith(new AppConfig());
        assertFalse(sm.shortenPathsEnabled());
    }

    @Test
    void setShortenPathsEnabled() {
        var sm = managerWith(new AppConfig());
        sm.setShortenPathsEnabled(true);
        assertTrue(sm.shortenPathsEnabled());
    }

    @Test
    void sessionDirDefault() {
        var sm = managerWith(new AppConfig());
        var dir = sm.sessionDir();
        assertTrue(dir.toString().contains(".pijota"));
    }

    @Test
    void sessionDirCustom() {
        var config = new AppConfig(null, null, null, null, null, null, "/custom/sessions", null, null, null, null,
                                        false, false, false, 3, 2000L, null);
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
        var config = new AppConfig(null, null, null, null, "/bin/zsh", null, null, null, null, null, null, false, false,
                                        false, 3, 2000L, null);
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
}
