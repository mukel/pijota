package ai.pijota.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AppConfig(@JsonProperty("default_provider") String defaultProvider,
                                @JsonProperty("default_model") String defaultModel,
                                @JsonProperty("api_keys") Map<String, String> apiKeys,
                                @JsonProperty("thinking_level") String thinkingLevel,
                                @JsonProperty("shell_path") String shellPath,
                                @JsonProperty("shell_command_prefix") String shellCommandPrefix,
                                @JsonProperty("session_dir") String sessionDir,
                                @JsonProperty("project_dir") String projectDir, @JsonProperty("theme") String theme,
                                @JsonProperty("themes") List<String> themes,
                                @JsonProperty("enabled_models") List<String> enabledModels,
                                @JsonProperty("context_files") Boolean contextFiles,
                                @JsonProperty("hide_thinking") boolean hideThinking,
                                @JsonProperty("quiet_startup") boolean quietStartup,
                                @JsonProperty("working_animation") String workingAnimation,
                                @JsonProperty("shorten_paths") boolean shortenPaths,
                                @JsonProperty("max_retries") int maxRetries,
                                @JsonProperty("retry_base_delay_ms") long retryBaseDelayMs,
                                @JsonProperty("compaction") CompactionConfig compaction) {
    public AppConfig() {
        this(null, null, Map.of(), "medium", "/bin/bash", null, null, null, null, List.of(), List.of(), true, false,
                                        false, "dots", false, 3, 2000L, new CompactionConfig());
    }

    public static AppConfig load(Path path) throws IOException {
        if (Files.exists(path)) {
            return Util.MAPPER.readValue(path.toFile(), AppConfig.class);
        }
        return new AppConfig();
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Util.MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), this);
    }

    public AppConfig withApiKey(String provider, String apiKey) {
        var updated = new HashMap<>(apiKeys != null ? apiKeys : Map.<String, String>of());
        updated.put(provider, apiKey);
        return new AppConfig(provider, defaultModel, updated, thinkingLevel, shellPath, shellCommandPrefix, sessionDir,
                                        null, theme, themes, enabledModels, contextFiles, hideThinking, quietStartup,
                                        workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs, compaction);
    }

    public AppConfig withoutApiKey(String provider) {
        var updated = new HashMap<>(apiKeys != null ? apiKeys : Map.<String, String>of());
        updated.remove(provider);
        return new AppConfig(defaultProvider, defaultModel, updated, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, contextFiles, hideThinking,
                                        quietStartup, workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs,
                                        compaction);
    }

    public AppConfig withDefaultProviderModel(String provider, String model) {
        return new AppConfig(provider, model, apiKeys, thinkingLevel, shellPath, shellCommandPrefix, sessionDir, null,
                                        theme, themes, enabledModels, contextFiles, hideThinking, quietStartup,
                                        workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs, compaction);
    }

    public AppConfig withContextFiles(boolean enabled) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, enabled, hideThinking,
                                        quietStartup, workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs,
                                        compaction);
    }

    public AppConfig withHideThinking(boolean enabled) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, contextFiles, enabled,
                                        quietStartup, workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs,
                                        compaction);
    }

    public AppConfig withQuietStartup(boolean enabled) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, contextFiles, hideThinking,
                                        enabled, workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs,
                                        compaction);
    }

    public AppConfig withThinkingLevel(String level) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, level, shellPath, shellCommandPrefix, sessionDir,
                                        null, theme, themes, enabledModels, contextFiles, hideThinking, quietStartup,
                                        workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs, compaction);
    }

    public AppConfig withShortenPaths(boolean enabled) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, contextFiles, hideThinking,
                                        quietStartup, workingAnimation, enabled, maxRetries, retryBaseDelayMs,
                                        compaction);
    }

    public AppConfig withSessionDir(String sessionDir) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, contextFiles, hideThinking,
                                        quietStartup, workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs,
                                        compaction);
    }

    public AppConfig withCompactionEnabled(boolean enabled) {
        var current = compaction != null ? compaction : new CompactionConfig();
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, contextFiles, hideThinking,
                                        quietStartup, workingAnimation, shortenPaths, maxRetries, retryBaseDelayMs,
                                        new CompactionConfig(enabled, current.reserveTokens(),
                                                                        current.keepRecentTokens()));
    }

    public AppConfig withWorkingAnimation(String name) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, null, theme, themes, enabledModels, contextFiles, hideThinking,
                                        quietStartup, name, shortenPaths, maxRetries, retryBaseDelayMs, compaction);
    }

    public AppConfig withProjectDir(String projectDir) {
        return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                        sessionDir, projectDir, theme, themes, enabledModels, contextFiles,
                                        hideThinking, quietStartup, workingAnimation, shortenPaths, maxRetries,
                                        retryBaseDelayMs, compaction);
    }

    public record CompactionConfig(@JsonProperty("enabled") boolean enabled,
                                    @JsonProperty("reserve_tokens") int reserveTokens,
                                    @JsonProperty("keep_recent_tokens") int keepRecentTokens) {
        public CompactionConfig() {
            this(true, 16384, 20000);
        }
    }
}
