package ai.pijota.llm;

import ai.pijota.util.Util;

import java.io.InputStream;
import java.util.List;

public final class ModelRegistry {

    private static final List<ModelInfo> LOADED_MODELS = load();

    public static final List<ModelInfo> KNOWN_MODELS = LOADED_MODELS.stream().toList();

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

    private static List<ModelInfo> load() {
        try (InputStream in = ModelRegistry.class.getResourceAsStream("/models.json")) {
            if (in == null) return List.of();
            var catalog = Util.MAPPER.readValue(in, ModelCatalog.class);
            return catalog.models();
        } catch (Exception e) {
            return List.of();
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelCatalog(List<ModelInfo> models) {
        public ModelCatalog {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
