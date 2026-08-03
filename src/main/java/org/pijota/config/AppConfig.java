package org.pijota.config;

import org.pijota.util.Platform;
import org.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
                                @JsonProperty("path_display") String pathDisplay,
                                @JsonProperty("default_project_trust") String defaultProjectTrust,
                                @JsonProperty("compaction") CompactionConfig compaction,
                                @JsonProperty("aliases") Map<String, String> aliases,
                                @JsonProperty("max_turns") Integer maxTurns,
                                @JsonProperty("providers") List<ProviderCatalog.Provider> providers,
                                @JsonProperty("extensions") Map<String, Object> extensions,
                                @JsonProperty("markdown") String markdown,
                                @JsonProperty("prompt") PromptGlyphs prompt) {

    /** Default per-prompt agent-turn cap when {@code max_turns} is unset. {@code 0} (or negative) means unlimited. */
    public static final int DEFAULT_MAX_TURNS = 256;

    public AppConfig() {
        // OS-appropriate default shell (Unix: /bin/bash, unchanged; Windows: PATH bash) instead of a hardcoded Unix
        // path that wouldn't exist on Windows. The bash tool stays bash (the model emits bash syntax), not the user's
        // $SHELL.
        this(null, null, Map.of(), "medium", Platform.defaultShell(), null, null, null, null, List.of(), List.of(),
                                        true, false, false, "dots", null, "ask", new CompactionConfig(), Map.of(), null,
                                        List.of(), Map.of(), "rich", new PromptGlyphs());
    }

    /** Back-compat positional constructor (pre-{@code prompt}); defaults the prompt glyphs. */
    public AppConfig(String defaultProvider, String defaultModel, Map<String, String> apiKeys, String thinkingLevel,
                                    String shellPath, String shellCommandPrefix, String sessionDir, String projectDir,
                                    String theme, List<String> themes, List<String> enabledModels, Boolean contextFiles,
                                    boolean hideThinking, boolean quietStartup, String workingAnimation,
                                    String pathDisplay, CompactionConfig compaction, Map<String, String> aliases,
                                    Integer maxTurns, List<ProviderCatalog.Provider> providers,
                                    Map<String, Object> extensions, String markdown) {
        this(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix, sessionDir,
                                        projectDir, theme, themes, enabledModels, contextFiles, hideThinking,
                                        quietStartup, workingAnimation, pathDisplay, "ask", compaction, aliases,
                                        maxTurns, providers, extensions, markdown, new PromptGlyphs());
    }

    public static AppConfig load(Path path) throws IOException {
        if (Files.exists(path)) {
            // A file holding literal "null" parses to a null object rather than throwing, so it slipped past the
            // caller's IOException fallback and NPE'd the startup with "Pijota failed" and no way back. Every other
            // malformation -- truncated, wrong types, an array -- already degraded to defaults; this one did not.
            var loaded = Util.MAPPER.readValue(path.toFile(), AppConfig.class);
            return loaded != null ? loaded : new AppConfig();
        }
        return new AppConfig();
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Util.restrictToOwner(path.getParent());
        String json = Util.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        // Skip the write when nothing changed: no churn (and no clobber risk) on a no-op save.
        if (Files.exists(path) && json.equals(Files.readString(path)))
            return;
        // Atomic write: serialize to a sibling .tmp, then rename over the real file, so a crash mid-write can never
        // truncate settings.json (which holds API keys).
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Util.restrictToOwner(tmp);
        Util.replaceFile(tmp, path);
        Util.restrictToOwner(path); // settings.json holds API keys, owner-only
    }

    public AppConfig withApiKey(String provider, String apiKey) {
        var updated = new HashMap<>(apiKeys != null ? apiKeys : Map.<String, String>of());
        updated.put(provider, apiKey);
        return toBuilder().defaultProvider(provider).apiKeys(updated).build();
    }

    public AppConfig withoutApiKey(String provider) {
        var updated = new HashMap<>(apiKeys != null ? apiKeys : Map.<String, String>of());
        updated.remove(provider);
        return toBuilder().apiKeys(updated).build();
    }

    public AppConfig withDefaultProviderModel(String provider, String model) {
        return toBuilder().defaultProvider(provider).defaultModel(model).build();
    }

    public AppConfig withContextFiles(boolean enabled) {
        return toBuilder().contextFiles(enabled).build();
    }

    public AppConfig withHideThinking(boolean enabled) {
        return toBuilder().hideThinking(enabled).build();
    }

    public AppConfig withQuietStartup(boolean enabled) {
        return toBuilder().quietStartup(enabled).build();
    }

    public AppConfig withThinkingLevel(String level) {
        return toBuilder().thinkingLevel(level).build();
    }

    public AppConfig withPathDisplay(String mode) {
        return toBuilder().pathDisplay(mode).build();
    }

    public AppConfig withSessionDir(String sessionDir) {
        return toBuilder().sessionDir(sessionDir).build();
    }

    public AppConfig withCompactionEnabled(boolean enabled) {
        var current = compaction != null ? compaction : new CompactionConfig();
        return toBuilder().compaction(new CompactionConfig(enabled, current.reserveTokens(),
                                        current.keepRecentTokens())).build();
    }

    public AppConfig withWorkingAnimation(String name) {
        return toBuilder().workingAnimation(name).build();
    }

    public AppConfig withProjectDir(String projectDir) {
        return toBuilder().projectDir(projectDir).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Mutable copy-builder so the {@code withX} helpers forward every field by name, eliminating the positional
     * copy-constructor churn (and the class of bug where an unrelated change silently nulled another field).
     */
    public static final class Builder {
        private String defaultProvider;
        private String defaultModel;
        private Map<String, String> apiKeys;
        private String thinkingLevel;
        private String shellPath;
        private String shellCommandPrefix;
        private String sessionDir;
        private String projectDir;
        private String theme;
        private List<String> themes;
        private List<String> enabledModels;
        private Boolean contextFiles;
        private boolean hideThinking;
        private boolean quietStartup;
        private String workingAnimation;
        private String pathDisplay;
        private String defaultProjectTrust;
        private CompactionConfig compaction;
        private Map<String, String> aliases;
        private Integer maxTurns;
        private List<ProviderCatalog.Provider> providers;
        private Map<String, Object> extensions;
        private String markdown;
        private PromptGlyphs prompt;

        private Builder(AppConfig c) {
            this.defaultProvider = c.defaultProvider;
            this.defaultModel = c.defaultModel;
            this.apiKeys = c.apiKeys;
            this.thinkingLevel = c.thinkingLevel;
            this.shellPath = c.shellPath;
            this.shellCommandPrefix = c.shellCommandPrefix;
            this.sessionDir = c.sessionDir;
            this.projectDir = c.projectDir;
            this.theme = c.theme;
            this.themes = c.themes;
            this.enabledModels = c.enabledModels;
            this.contextFiles = c.contextFiles;
            this.hideThinking = c.hideThinking;
            this.quietStartup = c.quietStartup;
            this.workingAnimation = c.workingAnimation;
            this.pathDisplay = c.pathDisplay;
            this.defaultProjectTrust = c.defaultProjectTrust;
            this.compaction = c.compaction;
            this.aliases = c.aliases;
            this.maxTurns = c.maxTurns;
            this.providers = c.providers;
            this.extensions = c.extensions;
            this.markdown = c.markdown;
            this.prompt = c.prompt;
        }

        public Builder defaultProvider(String v) {
            this.defaultProvider = v;
            return this;
        }

        public Builder defaultModel(String v) {
            this.defaultModel = v;
            return this;
        }

        public Builder apiKeys(Map<String, String> v) {
            this.apiKeys = v;
            return this;
        }

        public Builder thinkingLevel(String v) {
            this.thinkingLevel = v;
            return this;
        }

        public Builder sessionDir(String v) {
            this.sessionDir = v;
            return this;
        }

        public Builder projectDir(String v) {
            this.projectDir = v;
            return this;
        }

        public Builder contextFiles(Boolean v) {
            this.contextFiles = v;
            return this;
        }

        public Builder hideThinking(boolean v) {
            this.hideThinking = v;
            return this;
        }

        public Builder quietStartup(boolean v) {
            this.quietStartup = v;
            return this;
        }

        public Builder workingAnimation(String v) {
            this.workingAnimation = v;
            return this;
        }

        public Builder pathDisplay(String v) {
            this.pathDisplay = v;
            return this;
        }

        public Builder defaultProjectTrust(String v) {
            this.defaultProjectTrust = v;
            return this;
        }

        public Builder compaction(CompactionConfig v) {
            this.compaction = v;
            return this;
        }

        public Builder aliases(Map<String, String> v) {
            this.aliases = v;
            return this;
        }

        public Builder enabledModels(List<String> v) {
            this.enabledModels = v;
            return this;
        }

        public Builder maxTurns(Integer v) {
            this.maxTurns = v;
            return this;
        }

        public Builder providers(List<ProviderCatalog.Provider> v) {
            this.providers = v;
            return this;
        }

        public Builder extensions(Map<String, Object> v) {
            this.extensions = v;
            return this;
        }

        public Builder markdown(String v) {
            this.markdown = v;
            return this;
        }

        public Builder prompt(PromptGlyphs v) {
            this.prompt = v;
            return this;
        }

        public AppConfig build() {
            return new AppConfig(defaultProvider, defaultModel, apiKeys, thinkingLevel, shellPath, shellCommandPrefix,
                                            sessionDir, projectDir, theme, themes, enabledModels, contextFiles,
                                            hideThinking, quietStartup, workingAnimation, pathDisplay,
                                            defaultProjectTrust, compaction, aliases, maxTurns, providers, extensions,
                                            markdown, prompt);
        }
    }

    public record CompactionConfig(@JsonProperty("enabled") boolean enabled,
                                    @JsonProperty("reserve_tokens") int reserveTokens,
                                    @JsonProperty("keep_recent_tokens") int keepRecentTokens) {
        public CompactionConfig() {
            this(true, 16384, 20000);
        }
    }
}
