package ai.pijota.llm;

import java.util.Locale;

/** Shared, terminal-friendly formatting for model capabilities. */
public final class ModelDisplay {
    private ModelDisplay() {}

    public static String context(ModelInfo model) {
        return context(model != null ? model.contextWindow() : 0);
    }

    public static String context(long contextWindow) {
        return contextWindow > 0 ? (contextWindow / 1024) + "K" : "?";
    }

    public static String modalities(ModelInfo model) {
        if (model == null)
            return "unknown";
        var modalities = model.inputModalities();
        if (modalities == null)
            return "unknown";
        return modalities.stream().map(m -> m.name().toLowerCase(Locale.ROOT)).sorted().reduce((a, b) -> a + "," + b)
                                        .orElse("text");
    }

    public static String pricing(ModelInfo model) {
        if (model == null || model.inputCostPerMTok() == null || model.outputCostPerMTok() == null)
            return "unknown";
        return "$" + trimPrice(model.inputCostPerMTok()) + "/$" + trimPrice(model.outputCostPerMTok()) + "/MTok";
    }

    public static String summary(ModelInfo model) {
        return "context " + context(model) + " · input " + modalities(model) + " · price " + pricing(model);
    }

    private static String trimPrice(double value) {
        String s = String.format(Locale.ROOT, "%.4f", value);
        while (s.contains(".") && s.endsWith("0"))
            s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
