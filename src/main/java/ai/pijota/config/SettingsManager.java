package ai.pijota.config;

import ai.pijota.util.Util;

import java.nio.file.Path;
import java.util.List;

public final class SettingsManager {
    private static final Path CONFIG_HOME = Path.of(
        System.getProperty("user.home"), ".pijota");
    private static final Path SETTINGS_FILE = CONFIG_HOME.resolve("settings.json");
    private static final Path SESSION_DIR = CONFIG_HOME.resolve("sessions");

    private AppConfig config;

    public SettingsManager() {
        try {
            this.config = AppConfig.load(SETTINGS_FILE);
        } catch (Exception e) {
            this.config = new AppConfig();
        }
    }

    public AppConfig config() { return config; }

    public boolean contextFilesEnabled() {
        return config.contextFiles() == null || config.contextFiles();
    }

    public void setContextFilesEnabled(boolean enabled) {
        config = config.withContextFiles(enabled);
        save();
    }

    public void setHideThinking(boolean enabled) {
        config = config.withHideThinking(enabled);
        save();
    }

    public void setQuietStartup(boolean enabled) {
        config = config.withQuietStartup(enabled);
        save();
    }

    public boolean compactionEnabled() {
        return config.compaction() == null || config.compaction().enabled();
    }

    public void setCompactionEnabled(boolean enabled) {
        config = config.withCompactionEnabled(enabled);
        save();
    }

    public boolean shortenPathsEnabled() {
        return config.shortenPaths();
    }

    public void setShortenPathsEnabled(boolean enabled) {
        config = config.withShortenPaths(enabled);
        save();
    }

    public Path sessionDir() {
        String custom = config.sessionDir();
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }
        return SESSION_DIR;
    }

    public Path configHome() { return CONFIG_HOME; }

    public void save() {
        try {
            config.save(SETTINGS_FILE);
        } catch (Exception e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }

    public void saveApiKey(String provider, String apiKey, String defaultModel) throws java.io.IOException {
        config = config.withApiKey(provider, apiKey).withDefaultProviderModel(provider, defaultModel);
        config.save(SETTINGS_FILE);
    }

    public void removeApiKey(String provider) throws java.io.IOException {
        config = config.withoutApiKey(provider);
        config.save(SETTINGS_FILE);
    }

    /** Set the default provider/model without storing an API key (for OAuth). */
    public void setDefaultModelForProvider(String provider, String model) throws java.io.IOException {
        config = config.withDefaultProviderModel(provider, model);
        config.save(SETTINGS_FILE);
    }

    public String detectProviderFromEnv() {
        for (var provider : ProviderCatalog.providers().stream().map(ProviderCatalog.Provider::id).toList()) {
            if (resolveApiKey(provider) != null) return provider;
        }
        for (var provider : knownEnvProviders()) {
            if (resolveApiKey(provider) != null) return provider;
        }
        return null;
    }

    public String resolveApiKey(String provider) {
        var oauthStore = new OAuthTokenStore();
        String oauthToken = oauthStore.getAccessToken(provider);
        if (oauthToken != null && !oauthToken.isBlank()) return oauthToken;

        // For OAuth-capable providers, skip settings.json apiKeys — the token
        // there is a stale copy from before the OAuthTokenStore auto-refresh era.
        boolean isOAuthProvider = ProviderCatalog.oauth(provider).isPresent();

        if (isOAuthProvider) return null;

        String key = config.apiKeys() != null ? config.apiKeys().get(provider) : null;
        if (key != null) return key;
        for (String name : apiKeyEnvNames(provider)) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public String apiKeyHint(String provider) {
        return String.join(" or ", apiKeyEnvNames(provider));
    }

    private static List<String> knownEnvProviders() {
        return List.of("anthropic", "openai", "google", "deepseek", "groq",
            "openrouter", "xai", "mistral", "together", "fireworks", "cerebras");
    }

    private static List<String> apiKeyEnvNames(String provider) {
        var fromCatalog = ProviderCatalog.envNames(provider);
        if (!fromCatalog.isEmpty()) return fromCatalog;
        return switch (provider) {
            case "openai" -> List.of("OPENAI_API_KEY");
            case "anthropic" -> List.of("ANTHROPIC_API_KEY");
            case "google" -> List.of("GEMINI_API_KEY", "GOOGLE_API_KEY");
            case "deepseek" -> List.of("DEEPSEEK_API_KEY");
            case "groq" -> List.of("GROQ_API_KEY");
            case "xai" -> List.of("XAI_API_KEY");
            case "mistral" -> List.of("MISTRAL_API_KEY");
            case "together" -> List.of("TOGETHER_API_KEY");
            case "openrouter" -> List.of("OPENROUTER_API_KEY");
            case "cerebras" -> List.of("CEREBRAS_API_KEY");
            case "fireworks" -> List.of("FIREWORKS_API_KEY");
            default -> List.of(provider.toUpperCase().replace('-', '_') + "_API_KEY");
        };
    }

    public String effectiveShellPath() {
        String p = config.shellPath();
        if (p != null && !p.isBlank()) return p;
        return System.getenv().getOrDefault("SHELL", "/bin/bash");
    }
}
