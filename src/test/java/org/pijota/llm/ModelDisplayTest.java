package org.pijota.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelDisplayTest {

    @Test
    void contextFormatsKnownAndUnknownWindows() {
        assertEquals("125K", ModelDisplay.context(new ModelInfo("m", "p", "M", 128_000)));
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

        assertTrue(summary.contains("context 125K"), summary);
        assertTrue(summary.contains("input image,text"), summary);
        assertTrue(summary.contains("price $1/$2/MTok"), summary);
    }

    /**
     * Most decisive fact first, because a narrow selector row drops from the end (SelectorRegion.fitDetail). Price used
     * to be last, so a 100-column window showed every model as "context 976K · input text" -- the two facts you choose
     * a model by reduced to one, and the survivor was the one that is "text" for almost every model.
     */
    @Test
    void theFactALittleWindowLosesIsTheLeastUsefulOne() {
        var summary = ModelDisplay.summary(new ModelInfo("m", "p", "M", 128_000, 1.0, 2.0, List.of("image")));

        assertTrue(summary.indexOf("price") < summary.indexOf("input"), summary);
        assertTrue(summary.indexOf("context") < summary.indexOf("price"), summary);
    }
}
