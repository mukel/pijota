package ai.pijota.llm;

import ai.pijota.agent.SessionManager;
import ai.pijota.config.ProviderCatalog;
import ai.pijota.config.SettingsManager;
import ai.pijota.util.Util;

import java.io.InputStream;
import java.util.List;

public final class ModelRegistry {

    private static final long DEFAULT_CONTEXT_WINDOW = 128_000L;

    private static final List<ModelInfo> BUILTIN_MODELS = load();

    public static volatile List<ModelInfo> KNOWN_MODELS = BUILTIN_MODELS.stream().toList();

    public static synchronized void replaceDiscovered(List<ModelInfo> discovered) {
        var byKey = new java.util.LinkedHashMap<String, ModelInfo>();
        for (var model : BUILTIN_MODELS) {
            byKey.put(key(model), model);
        }
        for (var model : discovered) {
            byKey.put(key(model), model);
        }
        KNOWN_MODELS = List.copyOf(byKey.values());
    }

    public static List<ModelInfo> filter(String search) {
        if (search == null || search.isBlank()) return KNOWN_MODELS;
        String lower = search.toLowerCase();
        return KNOWN_MODELS.stream()
            .filter(m -> m.id().toLowerCase().contains(lower)
                || m.provider().toLowerCase().contains(lower)
                || m.displayName().toLowerCase().contains(lower))
            .toList();
    }

    public static List<ModelInfo> forProvider(String provider) {
        return KNOWN_MODELS.stream()
            .filter(m -> m.provider().equalsIgnoreCase(provider))
            .toList();
    }

    public static String defaultModelForProvider(String provider) {
        String fromCatalog = ProviderCatalog.defaultModel(provider);
        if (fromCatalog != null && !fromCatalog.isBlank()) return fromCatalog;
        return forProvider(provider).stream()
            .map(ModelInfo::id)
            .findFirst()
            .orElse(null);
    }

    public static String providerForModel(String modelId) {
        if (modelId == null) return null;
        return KNOWN_MODELS.stream()
            .filter(m -> m.id().equalsIgnoreCase(modelId))
            .findFirst()
            .map(ModelInfo::provider)
            .orElse(null);
    }

    public static long resolveContextWindow(String provider, String model) {
        if (provider == null || model == null) return DEFAULT_CONTEXT_WINDOW;
        return KNOWN_MODELS.stream()
            .filter(m -> m.provider().equalsIgnoreCase(provider) && m.id().equalsIgnoreCase(model))
            .findFirst()
            .or(() -> KNOWN_MODELS.stream()
                .filter(m -> m.id().equalsIgnoreCase(model))
                .findFirst())
            .map(ModelInfo::contextWindow)
            .orElse(DEFAULT_CONTEXT_WINDOW);
    }

    public static long resolveContextWindow(SessionManager.ModelSelection selection) {
        if (selection == null) return DEFAULT_CONTEXT_WINDOW;
        if (selection.contextWindow() > 0) return selection.contextWindow();
        return resolveContextWindow(selection.provider(), selection.model());
    }

    public static ResolvedModel resolve(SessionManager.ModelSelection selection, SettingsManager settings) {
        if (selection == null || settings == null) return null;
        String key = settings.resolveApiKey(selection.provider());
        if (key == null || key.isBlank()) return null;
        return new ResolvedModel(selection.provider(), selection.model(), key, resolveContextWindow(selection));
    }

    public static ResolvedModel resolve(String provider, String model, SettingsManager settings) {
        if (provider == null || model == null || settings == null) return null;
        String key = settings.resolveApiKey(provider);
        if (key == null || key.isBlank()) return null;
        return new ResolvedModel(provider, model, key, resolveContextWindow(provider, model));
    }

    public record ResolvedModel(String provider, String model, String apiKey, long contextWindow) {
        public int contextWindowInt() { return (int) Math.min(contextWindow, Integer.MAX_VALUE); }
    }

    public record ProviderModel(String provider, String model) {}

    public static ProviderModel resolveProviderModel(String explicitProvider, String explicitModel, SettingsManager settings) {
        String provider = explicitProvider;
        String model = explicitModel;
        if (provider == null && settings != null) provider = settings.config().defaultProvider();
        if (provider == null && model != null) provider = providerForModel(model);
        if (provider == null) provider = "openai";
        if (model == null) model = settings != null ? settings.config().defaultModel() : null;
        if (model == null) model = defaultModelForProvider(provider);
        return new ProviderModel(provider, model);
    }

    private static List<ModelInfo> load() {
        try (InputStream in = ModelRegistry.class.getResourceAsStream("/models.json")) {
            if (in == null) return List.of();
            var catalog = Util.MAPPER.readValue(in, ModelCatalog.class);
            return catalog.models();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String key(ModelInfo model) {
        return model.provider().toLowerCase() + "/" + model.id().toLowerCase();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelCatalog(List<ModelInfo> models) {
        public ModelCatalog {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
