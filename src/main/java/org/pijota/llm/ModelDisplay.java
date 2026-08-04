package org.pijota.llm;

import java.util.Locale;

/** Shared, terminal-friendly formatting for model capabilities. */
public final class ModelDisplay {
    private ModelDisplay() {}

    public static String context(ModelInfo model) {
        return context(model != null ? model.contextWindow() : 0);
    }

    /**
     * The window as its provider quotes it.
     *
     * <p>
     * Dividing by 1024 turned every advertised figure into a number nobody recognises: a 1,000,000-token model read
     * "976K" and a 272,000-token one "265K". Context windows are quoted in decimal thousands by every provider, and
     * this string is how a user matches what they see here against what they were sold.
     */
    public static String context(long contextWindow) {
        if (contextWindow <= 0)
            return "?";
        if (contextWindow >= 1_000_000) {
            double millions = contextWindow / 1_000_000.0;
            // 1M, not 1.0M; 1.5M keeps the half that distinguishes it.
            return (millions == Math.floor(millions)
                                            ? String.valueOf((long) millions)
                                            : String.format(Locale.ROOT, "%.1f", millions)) + "M";
        }
        return Math.round(contextWindow / 1000.0) + "K";
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

    /**
     * The facts about a model, most decisive first. Order is not cosmetic: a narrow terminal drops trailing facts (see
     * {@code SelectorRegion.fitDetail}), so whatever sits last is what a small window loses. Price and context are how
     * anyone actually chooses between two models; the modality list is almost always "text" and is the fact worth
     * losing first.
     */
    public static String summary(ModelInfo model) {
        return "context " + context(model) + " · price " + pricing(model) + " · input " + modalities(model);
    }

    /**
     * The summary shortened to {@code room} by dropping whole facts from the end, never by cutting through one.
     *
     * <p>
     * The order above is what makes this safe to do: the modality goes first and the context stays longest. A visual
     * truncation would leave {@code price $0.14/$} in a narrow terminal, a severed number that does not read as narrow
     * so much as broken; a shorter line that is entirely true is better. The TUI does the same thing to its selector
     * rows in {@code TuiCopy.fit}, which additionally has ANSI escapes to measure around -- these strings are plain.
     */
    public static String summary(ModelInfo model, int room) {
        String full = summary(model);
        if (room <= 0 || full.length() <= room)
            return room <= 0 ? "" : full;
        String[] facts = full.split(" · ", -1);
        var kept = new StringBuilder(facts[0]);
        for (int i = 1; i < facts.length && kept.length() + 3 + facts[i].length() <= room; i++)
            kept.append(" · ").append(facts[i]);
        // Even the first fact can be too wide; say so with an ellipsis rather than pretending it is whole.
        return kept.length() <= room ? kept.toString() : facts[0].substring(0, Math.max(1, room - 1)) + "…";
    }

    private static String trimPrice(double value) {
        String s = String.format(Locale.ROOT, "%.4f", value);
        while (s.contains(".") && s.endsWith("0"))
            s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
