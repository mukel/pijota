package org.pijota;

import org.pijota.config.ProviderCatalog;
import org.pijota.llm.ModelRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An empty {@code --list-models} result has more than one cause, and each wants a different next command. Naming the
 * wrong one sends someone to repair a setting that was never broken: a provider whose models are simply not bundled
 * looked identical to a typo, so the answer was "no model matching" and the road ended there.
 */
class NoModelsMessagePointsSomewhereTest {

    /** A provider pijota knows but has no models catalogued for. Baseten is one; the test finds one either way. */
    private static ProviderCatalog.Provider providerWithoutModels() {
        return ProviderCatalog.providers().stream().filter(p -> ModelRegistry.forProvider(p.id()).isEmpty()).findFirst()
                                        .orElseThrow(() -> new AssertionError("every provider has bundled models"));
    }

    private static ProviderCatalog.Provider providerWithModels() {
        return ProviderCatalog.providers().stream()
                                        .filter(p -> !ModelRegistry.forProvider(p.id()).isEmpty() && !p.env().isEmpty())
                                        .findFirst().orElseThrow(() -> new AssertionError("no catalogued provider"));
    }

    @Test
    void aProviderWithNothingCataloguedIsSentToRefreshModels() {
        var p = providerWithoutModels();

        String message = Main.noModelsMessage(p.id(), 33);

        assertTrue(message.contains("--refresh-models " + p.id()),
                                        "it has to name the command that fixes it: " + message);
        assertTrue(message.contains(p.displayName()), message);
        assertFalse(message.contains("No model matching"), "that is the typo message, not this: " + message);
    }

    /** Case matters at a prompt but not to a provider id. */
    @Test
    void theProviderIsRecognisedWhateverTheCase() {
        var p = providerWithoutModels();

        assertTrue(Main.noModelsMessage(p.id().toUpperCase(java.util.Locale.ROOT), 33).contains("--refresh-models"));
    }

    /** Catalogued but unreachable is a credentials problem, so it names the variable rather than a refresh. */
    @Test
    void aProviderWithModelsButNoKeyIsSentToItsApiKey() {
        var p = providerWithModels();

        String message = Main.noModelsMessage(p.id(), 33);

        assertTrue(message.contains(p.env().get(0)), "name the variable to set: " + message);
        assertFalse(message.contains("--refresh-models"), "refreshing will not help here: " + message);
    }

    /** A filter that is not a provider at all is still an ordinary miss, and must not be blamed on a provider. */
    @Test
    void aFilterThatMatchesNothingKeepsTheOrdinaryMessage() {
        String message = Main.noModelsMessage("zzzz-not-a-provider", 33);

        assertTrue(message.contains("No model matching"), message);
        assertFalse(message.contains("--refresh-models"), message);
    }

    /** Nothing usable at all is a setup problem, whether or not a filter was given. */
    @Test
    void withNothingReachableTheAnswerIsToSetAKey() {
        assertTrue(Main.noModelsMessage(null, 0).contains("login"), Main.noModelsMessage(null, 0));
        assertTrue(Main.noModelsMessage("zzzz-not-a-provider", 0).contains("login"));
    }
}
