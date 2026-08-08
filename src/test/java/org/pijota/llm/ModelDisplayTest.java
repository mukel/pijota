package org.pijota.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelDisplayTest {

    /**
     * As the provider quotes it. Dividing by 1024 turned every advertised figure into one nobody recognises: a
     * 272,000-token model read "265K" and a 1,000,000-token one "976K", and this string is how a user matches what they
     * see here against what they were sold. The old expectations pinned that arithmetic.
     */
    @Test
    void contextIsShownTheWayProvidersQuoteIt() {
        assertEquals("128K", ModelDisplay.context(new ModelInfo("m", "p", "M", 128_000)));
        assertEquals("272K", ModelDisplay.context(272_000));
        assertEquals("1M", ModelDisplay.context(1_000_000), "not 1.0M, and certainly not 976K");
        assertEquals("1.5M", ModelDisplay.context(1_500_000), "the half is what distinguishes it");
        assertEquals("2M", ModelDisplay.context(2_000_000));
        // The models.json catalogue quotes both: deepseek 1_000_000, kimi and gemini 2^20. They are the same window
        // to a reader, and a list that printed "1M" on one row and "1.0M" on the next said otherwise.
        assertEquals("1M", ModelDisplay.context(1_048_576), "2^20 is quoted as 1M, not 1.0M");
        assertEquals("2.1M", ModelDisplay.context(2_097_152), "but a real fraction still shows");
        assertEquals("?", ModelDisplay.context(0));
    }

    @Test
    void modalitiesIncludeTextAndSortDeclaredInputs() {
        var model = new ModelInfo("m", "p", "M", 128_000, null, null, List.of("image", "pdf"));

        assertEquals("image,pdf,text", ModelDisplay.modalities(model));
    }

    @Test
    void modalitiesAreUnknownWhenCatalogDoesNotDeclareThem() {
        assertEquals("unknown", ModelDisplay.modalities(new ModelInfo("m", "p", "M", 128_000)));
    }

    @Test
    void pricingFormatsKnownPricesAndUnknownPrices() {
        assertEquals("$1.25/$10/MTok", ModelDisplay.pricing(new ModelInfo("m", "p", "M", 128_000, 1.25, 10.0)));
        assertEquals("unknown", ModelDisplay.pricing(new ModelInfo("m", "p", "M", 128_000)));
    }

    @Test
    void summaryIncludesAllCapabilityFields() {
        var summary = ModelDisplay.summary(new ModelInfo("m", "p", "M", 128_000, 1.0, 2.0, List.of("image")));

        assertTrue(summary.contains("context 128K"), summary);
        assertTrue(summary.contains("input image,text"), summary);
        assertTrue(summary.contains("price $1/$2/MTok"), summary);
    }

    /**
     * Most decisive fact first, because a narrow selector row drops from the end (SelectorRegion.fitDetail). Price used
     * to be last, so a 100-column window showed every model as "context 128K · input text" -- the two facts you choose
     * a model by reduced to one, and the survivor was the one that is "text" for almost every model.
     */
    @Test
    void theFactALittleWindowLosesIsTheLeastUsefulOne() {
        var summary = ModelDisplay.summary(new ModelInfo("m", "p", "M", 128_000, 1.0, 2.0, List.of("image")));

        assertTrue(summary.indexOf("price") < summary.indexOf("input"), summary);
        assertTrue(summary.indexOf("context") < summary.indexOf("price"), summary);
    }
}
