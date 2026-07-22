package ai.pijota.llm;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A model in the catalog. {@code inputCostPerMTok}/{@code outputCostPerMTok} are USD per 1M tokens and may be
 * {@code null} when the rate is unknown (cost tracking then reports the spend as unpriced rather than guessing).
 * {@code pricingTiers} holds any long-context surcharge tiers ({@code null}/empty for the flat-rate majority).
 */
public record ModelInfo(String id, String provider, String displayName, long contextWindow, Double inputCostPerMTok,
                                Double outputCostPerMTok, List<String> input, List<PricingTier> pricingTiers) {

    /**
     * A long-context pricing tier: once a request's total input exceeds {@code inputTokensAbove}, these rates apply to
     * the whole request (e.g. xAI charges more above 200K). The highest matching tier wins; the base rate applies below
     * every tier.
     */
    public record PricingTier(long inputTokensAbove, Double inputCostPerMTok, Double outputCostPerMTok) {
    }

    /**
     * The model's declared input modalities, or {@code null} when the catalog doesn't specify them. Unknown tokens are
     * ignored; an explicit empty list parses to an empty set (text-only).
     */
    public Set<Modality> inputModalities() {
        if (input == null)
            return null;
        var set = EnumSet.of(Modality.TEXT); // text is implicit for every model
        for (String name : input) {
            Modality m = Modality.parse(name);
            if (m != null)
                set.add(m);
        }
        return set;
    }

    /** Flat-rate constructor; no long-context tiers. */
    public ModelInfo(String id, String provider, String displayName, long contextWindow, Double inputCostPerMTok,
                                    Double outputCostPerMTok, List<String> input) {
        this(id, provider, displayName, contextWindow, inputCostPerMTok, outputCostPerMTok, input, null);
    }

    /** Pricing-only constructor; input modalities default to unspecified (declare them in the catalog). */
    public ModelInfo(String id, String provider, String displayName, long contextWindow, Double inputCostPerMTok,
                                    Double outputCostPerMTok) {
        this(id, provider, displayName, contextWindow, inputCostPerMTok, outputCostPerMTok, null, null);
    }

    /** Convenience constructor for models with unknown pricing. */
    public ModelInfo(String id, String provider, String displayName, long contextWindow) {
        this(id, provider, displayName, contextWindow, null, null, null);
    }
}
