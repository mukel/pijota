package ai.pijota.config;

import ai.pijota.util.PijotaHome;
import ai.pijota.util.SelfRespawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public final class SettingsManager {
    private static final Logger logger = LoggerFactory.getLogger(SettingsManager.class);
    private static final Path CONFIG_HOME = PijotaHome.dir();
    private static final Path SETTINGS_FILE = CONFIG_HOME.resolve("settings.json");
    private static final Path SESSION_DIR = CONFIG_HOME.resolve("sessions");

    private AppConfig config;
    private final java.util.function.Consumer<AppConfig> onSave;

    public SettingsManager() {
        this(loadConfig(), SettingsManager::persistConfig);
    }

    public SettingsManager(AppConfig config, java.util.function.Consumer<AppConfig> onSave) {
        this.config = config;
        this.onSave = onSave;
        if (config.projectDir() == null || config.projectDir().isBlank()) {
            String detected = SelfRespawn.findGitRoot(System.getProperty("user.dir"));
            this.config = config.withProjectDir(detected);
            onSave.accept(this.config);
        }
    }

    private static AppConfig loadConfig() {
        try {
            return AppConfig.load(SETTINGS_FILE);
        } catch (Exception e) {
            return new AppConfig();
        }
    }

    private static void persistConfig(AppConfig config) {
        try {
            config.save(SETTINGS_FILE);
        } catch (Exception e) {
            logger.warn("Failed to save settings: {}", e.getMessage());
        }
    }

    public AppConfig config() {
        return config;
    }

    public boolean contextFilesEnabled() {
        return config.contextFiles() == null || config.contextFiles();
    }

    public void setContextFilesEnabled(boolean enabled) {
        config = config.withContextFiles(enabled);
        save();
    }

    public void setThinkingLevel(String level) {
        config = config.withThinkingLevel(level);
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

    public String workingAnimation() {
        return config.workingAnimation() != null ? config.workingAnimation() : "dots";
    }

    public void setWorkingAnimation(String name) {
        config = config.withWorkingAnimation(name);
        save();
    }

    public String projectDir() {
        String dir = config.projectDir();
        if (dir != null && !dir.isBlank())
            return dir;
        return SelfRespawn.findGitRoot(System.getProperty("user.dir"));
    }

    public Path sessionDir() {
        String custom = config.sessionDir();
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }
        return SESSION_DIR;
    }

    public Path configHome() {
        return CONFIG_HOME;
    }

    public void save() {
        onSave.accept(config);
    }

    public void saveApiKey(String provider, String apiKey, String defaultModel) throws java.io.IOException {
        config = config.withApiKey(provider, apiKey).withDefaultProviderModel(provider, defaultModel);
        onSave.accept(config);
    }

    public void removeApiKey(String provider) throws java.io.IOException {
        config = config.withoutApiKey(provider);
        onSave.accept(config);
    }

    /** Set the default provider/model without storing an API key (for OAuth). */
    public void setDefaultModelForProvider(String provider, String model) throws java.io.IOException {
        config = config.withDefaultProviderModel(provider, model);
        onSave.accept(config);
    }

    public String detectProviderFromEnv() {
        for (var provider : ProviderCatalog.providers().stream().map(ProviderCatalog.Provider::id).toList()) {
            if (resolveApiKey(provider) != null)
                return provider;
        }
        for (var provider : knownEnvProviders()) {
            if (resolveApiKey(provider) != null)
                return provider;
        }
        return null;
    }

    /** Returns true if the provider requires an API key (has env vars or OAuth). */
    public static boolean requiresApiKey(String provider) {
        if (ProviderCatalog.oauth(provider).isPresent())
            return true;
        return !ProviderCatalog.envNames(provider).isEmpty();
    }

    public String resolveApiKey(String provider) {
        // Providers with no env vars and no OAuth don't need an API key
        if (!requiresApiKey(provider))
            return "";

        var oauthStore = new OAuthTokenStore();
        String oauthToken = oauthStore.getAccessToken(provider);
        if (oauthToken != null && !oauthToken.isBlank())
            return oauthToken;

        String key = config.apiKeys() != null ? config.apiKeys().get(provider) : null;
        if (key != null)
            return key;
        for (String name : apiKeyEnvNames(provider)) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank())
                return value;
        }
        return null;
    }

    public String apiKeyHint(String provider) {
        return String.join(" or ", apiKeyEnvNames(provider));
    }

    private static List<String> knownEnvProviders() {
        return List.of("anthropic", "openai", "google", "deepseek", "groq", "openrouter", "xai", "mistral", "together",
                                        "fireworks", "cerebras");
    }

    private static List<String> apiKeyEnvNames(String provider) {
        var fromCatalog = ProviderCatalog.envNames(provider);
        if (!fromCatalog.isEmpty())
            return fromCatalog;
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
        if (p != null && !p.isBlank())
            return p;
        return System.getenv().getOrDefault("SHELL", "/bin/bash");
    }
}
