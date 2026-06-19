package ai.pijota.llm;

/**
 * A model in the catalog. {@code inputCostPerMTok}/{@code outputCostPerMTok} are USD per 1M tokens and may be
 * {@code null} when the rate is unknown (cost tracking then reports the spend as unpriced rather than guessing).
 */
public record ModelInfo(String id, String provider, String displayName, long contextWindow, Double inputCostPerMTok,
                                Double outputCostPerMTok) {

    /** Convenience constructor for models with unknown pricing. */
    public ModelInfo(String id, String provider, String displayName, long contextWindow) {
        this(id, provider, displayName, contextWindow, null, null);
    }
}
