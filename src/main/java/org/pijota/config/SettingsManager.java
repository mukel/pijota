package org.pijota.config;

import org.pijota.llm.ThinkingLevel;
import org.pijota.util.ErrorUtil;
import org.pijota.util.PijotaHome;
import org.pijota.util.Platform;
import org.pijota.util.SelfRespawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
        // Both channels on purpose. The log keeps the record; stderr is where the user is. These warnings say a
        // value in their own settings.json is being ignored, and for as long as they went only to a file, the file
        // said one thing and every session did another with nothing to connect the two -- which is the whole
        // complaint they exist to answer. stderr, not stdout, so piping a --print run stays clean.
        validate(this.config).forEach(w -> {
            logger.warn("settings warning: {}", w);
            System.err.println("pijota: " + w);
        });
    }

    public static List<String> validate(AppConfig config) {
        var warnings = new ArrayList<String>();
        if (config == null)
            return warnings;
        var trustValues = List.of("ask", "always", "never");
        String trust = config.defaultProjectTrust();
        if (trust != null && !trust.isBlank() && !trustValues.contains(trust))
            warnings.add(notOneOf("default_project_trust", trust, trustValues));
        String pathDisplay = config.pathDisplay();
        if (pathDisplay != null && !pathDisplay.isBlank() && !PathDisplay.isKnown(pathDisplay))
            warnings.add(notOneOf("path_display", pathDisplay, PathDisplay.names()));
        // Validated here with its siblings, and only here: checking the merged flag-then-settings value instead meant
        // a typo'd --thinking was reported twice, the second time blaming a settings.json key the user never touched.
        String thinking = config.thinkingLevel();
        if (thinking != null && !thinking.isBlank() && !ThinkingLevel.isKnown(thinking))
            warnings.add(notOneOf("thinking_level", thinking, ThinkingLevel.names()));
        var animationValues = List.of("dots", "moon", "bar", "braille", "none");
        String animation = config.workingAnimation();
        if (animation != null && !animation.isBlank() && !animationValues.contains(animation))
            warnings.add(notOneOf("working_animation", animation, animationValues));
        if (config.maxTurns() != null && config.maxTurns() < 0)
            warnings.add("max_turns below 0 is treated as unlimited; use 0 for that explicitly");
        warnings.addAll(ProviderCatalog.validateUserProviders(config.providers()));
        return List.copyOf(warnings);
    }

    /** How much of a bad value is echoed back before it is cut; long enough to recognise, short enough to read. */
    private static final int PRINTED_VALUE_LIMIT = 40;

    /**
     * Names what the file says as well as what it should say.
     *
     * <p>
     * Listing only the legal values leaves the reader going back to their own file to work out which of them they
     * missed, which is the one thing they already know they got wrong.
     */
    private static String notOneOf(String key, String value, List<String> allowed) {
        return key + " is " + printableQuoted(value) + "; it should be " + join(allowed);
    }

    /**
     * A value from settings.json, safe to put on a line of its own.
     *
     * <p>
     * These warnings go to stderr before there is any terminal handling to pass them through, and the text is the
     * user's, not ours: an ESC among it would be a command rather than a character. Controls become '?' and a long
     * value is cut, so a mistyped setting cannot repaint the screen it is complaining on.
     */
    private static String printableQuoted(String value) {
        var out = new StringBuilder("\"");
        value.codePoints().limit(PRINTED_VALUE_LIMIT)
                                        .forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
        return out.append(value.codePointCount(0, value.length()) > PRINTED_VALUE_LIMIT ? "…\"" : "\"").toString();
    }

    /** "a, b, or c" -- the wording every warning above already used, said once. */
    private static String join(List<String> values) {
        if (values.size() < 2)
            return String.join("", values);
        return String.join(", ", values.subList(0, values.size() - 1)) + ", or " + values.getLast();
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

        String key = resolveApiKeyOnly(provider);
        return key == null || key.isBlank() ? null : key;
    }

    /**
     * The static API key only: settings (with {@code ${ENV}} interpolation), then the provider's env vars. Unlike
     * {@link #resolveApiKey} this never returns an OAuth access token, which is what {@code pijota auth
     * print-api-key} promises; an OAuth bearer token used as an API key fails in ways that look like a server error.
     */
    public String resolveApiKeyOnly(String provider) {
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

    /**
     * The command to open for {@code Ctrl+E}: the {@code editor} setting, then {@code $VISUAL} / {@code $EDITOR}, then
     * a per-OS default.
     *
     * <p>
     * The setting is asked first for the reason it exists: {@code $EDITOR} belongs to the shell and is usually set to
     * whatever that user wants for git, while this one was written down for pijota. {@code $VISUAL} is consulted before
     * {@code $EDITOR} the way every other tool does it -- the two differ exactly when one is a full-screen editor and
     * the other a line editor, and this opens a full screen.
     */
    public String effectiveEditor() {
        String configured = config.editor();
        if (configured != null && !configured.isBlank())
            return configured.trim();
        for (String name : List.of("VISUAL", "EDITOR")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank())
                return value.trim();
        }
        return Platform.isWindows() ? "notepad" : "vi";
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
