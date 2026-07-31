package org.pijota.config;

import org.pijota.util.ErrorUtil;
import org.pijota.util.PijotaHome;
import org.pijota.util.Platform;
import org.pijota.util.SelfRespawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class SettingsManager {
    private static final Logger logger = LoggerFactory.getLogger(SettingsManager.class);

    private AppConfig config;
    private final Consumer<AppConfig> onSave;

    public SettingsManager() {
        this(loadConfig(), SettingsManager::persistConfig);
    }

    public SettingsManager(AppConfig config, Consumer<AppConfig> onSave) {
        this.config = config;
        this.onSave = onSave;
        // Resolve project_dir in-memory when unset, but DON'T persist it. Rewriting settings.json on every startup is a
        // gratuitous write that risked clobbering the file; projectDir() recomputes the git root on demand, so there's
        // no need to save it. Settings are written only on an explicit change.
        if (config.projectDir() == null || config.projectDir().isBlank()) {
            this.config = config.withProjectDir(SelfRespawn.findGitRoot(System.getProperty("user.dir")));
        }
        validate(this.config).forEach(w -> logger.warn("settings warning: {}", w));
    }

    public static List<String> validate(AppConfig config) {
        var warnings = new java.util.ArrayList<String>();
        if (config == null)
            return warnings;
        String trust = config.defaultProjectTrust();
        if (trust != null && !trust.isBlank() && !List.of("ask", "always", "never").contains(trust))
            warnings.add("default_project_trust should be ask, always, or never");
        String pathDisplay = config.pathDisplay();
        if (pathDisplay != null && !pathDisplay.isBlank()
                                        && !List.of("full", "relative", "compact", "name").contains(pathDisplay))
            warnings.add("path_display should be full, relative, compact, or name");
        String animation = config.workingAnimation();
        if (animation != null && !animation.isBlank()
                                        && !List.of("dots", "moon", "bar", "braille", "none").contains(animation))
            warnings.add("working_animation should be dots, moon, bar, braille, or none");
        if (config.maxTurns() != null && config.maxTurns() < 0)
            warnings.add("max_turns below 0 is treated as unlimited; use 0 for that explicitly");
        warnings.addAll(ProviderCatalog.validateUserProviders(config.providers()));
        return List.copyOf(warnings);
    }

    private static AppConfig loadConfig() {
        try {
            return AppConfig.load(PijotaHome.resolve("settings.json"));
        } catch (Exception e) {
            // A corrupt/unreadable settings.json otherwise silently reverts to defaults. Surface why.
            logger.warn("Could not read settings.json ({}); using defaults", ErrorUtil.message(e));
            return new AppConfig();
        }
    }

    private static void persistConfig(AppConfig config) {
        try {
            config.save(PijotaHome.resolve("settings.json"));
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

    public PathDisplay pathDisplay() {
        return PathDisplay.fromConfig(config.pathDisplay());
    }

    public void setPathDisplay(PathDisplay mode) {
        config = config.withPathDisplay(mode.config());
        save();
    }

    /** Advance to the next path-display mode (full → relative → compact → name → …) and return it. */
    public PathDisplay cyclePathDisplay() {
        PathDisplay next = pathDisplay().next();
        setPathDisplay(next);
        return next;
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
        return PijotaHome.resolve("sessions");
    }

    public Path configHome() {
        return PijotaHome.dir();
    }

    public void save() {
        onSave.accept(config);
    }

    public void saveApiKey(String provider, String apiKey, String defaultModel) throws IOException {
        config = config.withApiKey(provider, apiKey).withDefaultProviderModel(provider, defaultModel);
        onSave.accept(config);
    }

    public void removeApiKey(String provider) throws IOException {
        config = config.withoutApiKey(provider);
        onSave.accept(config);
    }

    /** Set the default provider/model without storing an API key (for OAuth). */
    public void setDefaultModelForProvider(String provider, String model) throws IOException {
        config = config.withDefaultProviderModel(provider, model);
        onSave.accept(config);
    }

    public String detectProviderFromEnv() {
        for (var provider : ProviderCatalog.providers().stream().map(ProviderCatalog.Provider::id).toList()) {
            if (resolveApiKey(provider) != null)
                return provider;
        }
        return null;
    }

    /** True for providers that authenticate via env vars or OAuth (i.e. not keyless). */
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
        if (key != null) {
            key = ProviderCatalog.interpolateEnv(key);
            if (key != null && !key.isBlank())
                return key;
        }
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

    /**
     * Env var names for a provider's API key: the catalog is the source of truth; unknown providers get a convention.
     */
    private static List<String> apiKeyEnvNames(String provider) {
        var fromCatalog = ProviderCatalog.envNames(provider);
        if (!fromCatalog.isEmpty())
            return fromCatalog;
        return List.of(provider.toUpperCase().replace('-', '_') + "_API_KEY");
    }

    /** Per-prompt agent-turn cap. Unset → {@link AppConfig#DEFAULT_MAX_TURNS}; {@code 0} or negative → unlimited. */
    public int maxTurns() {
        return config.maxTurns() != null ? config.maxTurns() : AppConfig.DEFAULT_MAX_TURNS;
    }

    public String effectiveShellPath() {
        String p = config.shellPath();
        if (p != null && !p.isBlank())
            return p;
        String env = System.getenv("SHELL");
        if (env != null && !env.isBlank())
            return env;
        return Platform.defaultShell();
    }
}
