package ai.pijota.llm;

import ai.pijota.agent.ModelSelection;

import ai.pijota.config.ProviderCatalog;
import ai.pijota.config.SettingsManager;
import ai.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class ModelRegistry {

    private static final long DEFAULT_CONTEXT_WINDOW = 128_000L;

    private static final List<ModelInfo> BUILTIN_MODELS = load();

    public static volatile List<ModelInfo> KNOWN_MODELS = BUILTIN_MODELS.stream().toList();

    public static synchronized void replaceDiscovered(List<ModelInfo> discovered) {
        var byKey = new LinkedHashMap<String, ModelInfo>();
        for (var model : BUILTIN_MODELS) {
            byKey.put(key(model), model);
        }
        for (var model : discovered) {
            byKey.put(key(model), model);
        }
        KNOWN_MODELS = List.copyOf(byKey.values());
    }

    public static List<ModelInfo> filter(String search) {
        if (search == null || search.isBlank())
            return KNOWN_MODELS;
        String lower = search.toLowerCase(Locale.ROOT);
        return KNOWN_MODELS.stream().filter(m -> m.id().toLowerCase(Locale.ROOT).contains(lower)
                                        || m.provider().toLowerCase(Locale.ROOT).contains(lower)
                                        || m.displayName().toLowerCase(Locale.ROOT).contains(lower)).toList();
    }

    public static List<ModelInfo> forProvider(String provider) {
        return KNOWN_MODELS.stream().filter(m -> m.provider().equalsIgnoreCase(provider)).toList();
    }

    public static String defaultModelForProvider(String provider) {
        String fromCatalog = ProviderCatalog.defaultModel(provider);
        // A catalogued provider default that's also a known model is the best pick. Otherwise fall back to the first
        // known model for the provider — but if there are none (e.g. a user-defined/local provider whose models aren't
        // in the catalog), honor the declared default verbatim; model ids are free-form and reach the API as-is.
        if (fromCatalog != null && !fromCatalog.isBlank() && isKnownProviderModel(provider, fromCatalog)) {
            return fromCatalog;
        }
        String firstKnown = forProvider(provider).stream().map(ModelInfo::id).findFirst().orElse(null);
        if (firstKnown != null)
            return firstKnown;
        return fromCatalog != null && !fromCatalog.isBlank() ? fromCatalog : null;
    }

    /** Look up a known model by its id (case-insensitive); empty if none matches. The one place that scans by id. */
    public static Optional<ModelInfo> byId(String id) {
        if (id == null)
            return Optional.empty();
        return KNOWN_MODELS.stream().filter(m -> m.id().equalsIgnoreCase(id)).findFirst();
    }

    public static String providerForModel(String modelId) {
        return byId(modelId).map(ModelInfo::provider).orElse(null);
    }

    /**
     * The catalogued model for {@code provider} closest to {@code model} — a likely-typo suggestion. Returns a result
     * only when a known id is near enough (small edit distance) to be a confident "did you mean", so genuinely new /
     * uncatalogued models yield {@link Optional#empty()} rather than a misleading nudge.
     */
    public static Optional<String> closestModel(String provider, String model) {
        if (provider == null || model == null || model.isBlank())
            return Optional.empty();
        String lower = model.toLowerCase(Locale.ROOT);
        int threshold = Math.max(2, model.length() / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (var info : forProvider(provider)) {
            int distance = levenshtein(lower, info.id().toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = info.id();
            }
        }
        return bestDistance <= threshold ? Optional.of(best) : Optional.empty();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
            prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    public static long resolveContextWindow(String provider, String model) {
        if (provider == null || model == null)
            return DEFAULT_CONTEXT_WINDOW;
        return KNOWN_MODELS.stream().filter(
                                        m -> m.provider().equalsIgnoreCase(provider) && m.id().equalsIgnoreCase(model))
                                        .findFirst().or(() -> byId(model)).map(ModelInfo::contextWindow)
                                        .orElse(DEFAULT_CONTEXT_WINDOW);
    }

    /** Per-million-token USD rates for a catalog model, or {@code null} when the model's pricing is unknown. */
    public record Pricing(double inputPerMTok, double outputPerMTok) {
    }

    /** The catalog model matching {@code model} (case-insensitive), preferring a provider match; empty if none. A
     *  null provider matches any provider. */
    private static Optional<ModelInfo> find(String provider, String model) {
        if (model == null)
            return Optional.empty();
        return KNOWN_MODELS.stream().filter(m -> m.id().equalsIgnoreCase(model)
                                        && (provider == null || provider.equalsIgnoreCase(m.provider()))).findFirst();
    }

    /**
     * The model's accepted input modalities, or {@code null} when unknown. A catalog model that doesn't declare its
     * modalities is treated as text-only; a model absent from the catalog returns {@code null} so callers can be
     * lenient (don't block a brand-new model).
     */
    public static Set<Modality> inputModalities(String provider, String model) {
        if (model == null)
            return EnumSet.of(Modality.TEXT);
        var found = find(provider, model);
        if (found.isEmpty())
            return null;
        var mods = found.get().inputModalities();
        return mods != null ? mods : EnumSet.of(Modality.TEXT);
    }

    /** Whether the model accepts the given modality; an unknown model (null modalities) is allowed (not blocked). */
    public static boolean supports(String provider, String model, Modality modality) {
        var mods = inputModalities(provider, model);
        return mods == null || mods.contains(modality);
    }

    /** Looks up pricing for the given model, preferring a provider match; {@code null} if the rate is unknown. */
    public static Pricing pricingFor(String provider, String model) {
        return find(provider, model)
                                        .filter(m -> m.inputCostPerMTok() != null && m.outputCostPerMTok() != null)
                                        .map(m -> new Pricing(m.inputCostPerMTok(), m.outputCostPerMTok())).orElse(null);
    }

    public static long resolveContextWindow(ModelSelection selection) {
        if (selection == null)
            return DEFAULT_CONTEXT_WINDOW;
        if (selection.contextWindow() > 0)
            return selection.contextWindow();
        return resolveContextWindow(selection.provider(), selection.model());
    }

    public static ResolvedModel resolve(ModelSelection selection, SettingsManager settings) {
        if (selection == null || settings == null)
            return null;
        var providerModel = resolveProviderModel(selection.provider(), selection.model(), settings);
        String key = settings.resolveApiKey(providerModel.provider());
        if (key == null || key.isBlank())
            return null;
        long contextWindow = selection.provider().equalsIgnoreCase(providerModel.provider())
                                        && selection.model().equalsIgnoreCase(providerModel.model())
                                        && selection.contextWindow() > 0
                                                                        ? selection.contextWindow()
                                                                        : resolveContextWindow(providerModel.provider(),
                                                                                                        providerModel.model());
        return new ResolvedModel(providerModel.provider(), providerModel.model(), key, contextWindow);
    }

    public static ResolvedModel resolve(String provider, String model, SettingsManager settings) {
        if (provider == null || model == null || settings == null)
            return null;
        var providerModel = resolveProviderModel(provider, model, settings);
        String key = settings.resolveApiKey(providerModel.provider());
        if (key == null || key.isBlank())
            return null;
        return new ResolvedModel(providerModel.provider(), providerModel.model(), key,
                                        resolveContextWindow(providerModel.provider(), providerModel.model()));
    }

    public record ResolvedModel(String provider, String model, String apiKey, long contextWindow) {
    }

    public record ProviderModel(String provider, String model) {
    }

    /**
     * Reserved provider sentinel: {@code alias/<name>} selects a configured model alias (see {@link #resolveAlias}).
     */
    public static final String ALIAS_PROVIDER = "alias";

    /** Reserved dynamic alias: {@code alias/current} resolves to the session's live model (not a config entry). */
    public static final String CURRENT_ALIAS = "current";

    /**
     * Split a {@code "provider/model"} spec. With no usable slash, provider is null and the whole string is the model.
     */
    public static ProviderModel parseProviderModel(String spec) {
        if (spec == null)
            return new ProviderModel(null, null);
        int slash = spec.indexOf('/');
        if (slash > 0 && slash < spec.length() - 1)
            return new ProviderModel(spec.substring(0, slash), spec.substring(slash + 1));
        return new ProviderModel(null, spec);
    }

    /**
     * Resolve an alias name (e.g. {@code "fast"}) from settings {@code "aliases"} to its target, or null if undefined.
     */
    public static ProviderModel resolveAlias(String aliasName, SettingsManager settings) {
        if (aliasName == null || settings == null)
            return null;
        var aliases = settings.config().aliases();
        String target = aliases != null ? aliases.get(aliasName) : null;
        if (target == null || target.isBlank())
            return null;
        var pm = parseProviderModel(target);
        return pm.provider() != null ? pm : new ProviderModel(providerForModel(target), target);
    }

    public static ProviderModel resolveProviderModel(String explicitProvider, String explicitModel,
                                    SettingsManager settings) {
        // Model aliases: "alias/<name>" resolves to a provider/model configured under settings "aliases"
        // (e.g. alias/fast -> anthropic/claude-haiku-4-5). Lets agents/sub-agents declare a role, not a concrete model.
        // Returned verbatim — an explicit alias target is honored as-is (supports custom/local models not in the
        // catalog, and avoids silently substituting the provider default for an off-catalog id).
        if (ALIAS_PROVIDER.equalsIgnoreCase(explicitProvider) && explicitModel != null && settings != null) {
            var target = resolveAlias(explicitModel, settings);
            if (target != null)
                return target;
        }
        String provider = explicitProvider;
        String model = explicitModel;
        if (provider == null && model != null)
            provider = providerForModel(model);
        if (provider == null && settings != null)
            provider = settings.config().defaultProvider();
        if (provider == null)
            provider = "openai";
        if (model == null)
            model = settings != null ? settings.config().defaultModel() : null;
        // Honor an explicit model verbatim — only fall back to the provider default when none was given. Off-catalog
        // ids (alias targets, local fine-tunes, models newer than the bundled catalog) must reach the provider API
        // as-is; silently swapping them for the default would diverge the live model from the UI and the session.
        if (model == null || model.isBlank()) {
            model = defaultModelForProvider(provider);
        }
        return new ProviderModel(provider, model);
    }

    /**
     * Allowlist match for the model picker: true if {@code m} matches one of the glob {@code patterns} (matched against
     * {@code "provider/id"} or just {@code id}, case-insensitive, {@code *}/{@code ?} wildcards). An empty/null list
     * matches everything (no allowlist configured).
     */
    public static boolean isEnabled(ModelInfo m, List<String> patterns) {
        if (patterns == null || patterns.isEmpty())
            return true;
        String full = m.provider() + "/" + m.id();
        for (String p : patterns) {
            if (p == null || p.isBlank())
                continue;
            String regex = globToRegex(p.trim());
            if (full.matches(regex) || m.id().matches(regex))
                return true;
        }
        return false;
    }

    /**
     * The known models worth offering in a picker: their provider has a usable (non-blank) API key configured, and they
     * pass the user's {@code enabled_models} allowlist. Null settings → empty (nothing is runnable without keys). The
     * single source of truth for every frontend's model picker — TUI, web and Telegram — so "which models can I switch
     * to" is answered identically everywhere (key check + allowlist), not re-derived per frontend.
     */
    public static List<ModelInfo> availableModels(SettingsManager settings) {
        if (settings == null)
            return KNOWN_MODELS; // settings unknown → don't filter (production always has settings; this aids tests)
        var withKeys = KNOWN_MODELS.stream().filter(m -> {
            // Show models we can actually use: either a key is configured, or the provider is keyless (a local /
            // no-auth OpenAI-compatible server) — otherwise a keyless custom provider's models never show in /model.
            String key = settings.resolveApiKey(m.provider());
            return (key != null && !key.isBlank()) || !SettingsManager.requiresApiKey(m.provider());
        }).toList();
        return applyAllowlist(withKeys, settings.config().enabledModels());
    }

    /**
     * Apply the {@code enabled_models} glob allowlist to a model list (shared by every frontend's picker). Fails open —
     * if the patterns would exclude everything, the unfiltered list is returned so a bad pattern can't lock the user
     * out of switching models. Null/empty patterns return the list unchanged.
     */
    public static List<ModelInfo> applyAllowlist(List<ModelInfo> models, List<String> patterns) {
        if (patterns == null || patterns.isEmpty())
            return models;
        var filtered = models.stream().filter(m -> isEnabled(m, patterns)).toList();
        return filtered.isEmpty() ? models : filtered;
    }

    private static String globToRegex(String glob) {
        var sb = new StringBuilder("(?i)");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*')
                sb.append(".*");
            else if (c == '?')
                sb.append('.');
            else
                sb.append(Pattern.quote(String.valueOf(c)));
        }
        return sb.toString();
    }

    public static boolean isKnownProviderModel(String provider, String model) {
        if (provider == null || model == null)
            return false;
        return KNOWN_MODELS.stream().anyMatch(
                                        m -> m.provider().equalsIgnoreCase(provider) && m.id().equalsIgnoreCase(model));
    }

    private static List<ModelInfo> load() {
        try (InputStream in = ModelRegistry.class.getResourceAsStream("/models.json")) {
            if (in == null)
                return List.of();
            var catalog = Util.MAPPER.readValue(in, ModelCatalog.class);
            return catalog.models();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String key(ModelInfo model) {
        return model.provider().toLowerCase(Locale.ROOT) + "/" + model.id().toLowerCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelCatalog(List<ModelInfo> models) {
        public ModelCatalog {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
