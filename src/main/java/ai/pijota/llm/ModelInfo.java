package ai.pijota.llm;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A model in the catalog. {@code inputCostPerMTok}/{@code outputCostPerMTok} are USD per 1M tokens and may be
 * {@code null} when the rate is unknown (cost tracking then reports the spend as unpriced rather than guessing).
 */
public record ModelInfo(String id, String provider, String displayName, long contextWindow, Double inputCostPerMTok,
                                Double outputCostPerMTok, List<String> input) {

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

    /** Pricing-only constructor; input modalities default to unspecified (declare them in the catalog). */
    public ModelInfo(String id, String provider, String displayName, long contextWindow, Double inputCostPerMTok,
                                    Double outputCostPerMTok) {
        this(id, provider, displayName, contextWindow, inputCostPerMTok, outputCostPerMTok, null);
    }

    /** Convenience constructor for models with unknown pricing. */
    public ModelInfo(String id, String provider, String displayName, long contextWindow) {
        this(id, provider, displayName, contextWindow, null, null, null);
    }
}
