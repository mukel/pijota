package org.pijota.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.cli.ArgParser;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the resolution rules extracted from AppBootstrap. The missing-key + typo-warning paths are
 * env-sensitive and stay covered end-to-end by AppBootstrapTest (which uses a custom keyless provider).
 */
class ModelResolverTest {

    private static SettingsManager settings(Map<String, String> aliases, String defaultModel) {
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", defaultModel)
                                        .toBuilder().aliases(aliases).build();
        return new SettingsManager(config, ignored -> {});
    }

    @Test
    void resolvesExplicitProviderModelWithKey() {
        var r = ModelResolver.resolve(ArgParser.parse(new String[] {"--model", "openai/gpt-5.5"}),
                                        settings(Map.of(), "gpt-5.5"));
        assertEquals("openai", r.provider());
        assertEquals("gpt-5.5", r.model());
        assertEquals("sk-test", r.apiKey());
    }

    @Test
    void printModeUsesThePrintAliasWhenNoExplicitModel() {
        var r = ModelResolver.resolve(ArgParser.parse(new String[] {"--print", "hi"}),
                                        settings(Map.of("print", "openai/gpt-4.1-mini"), "gpt-5.5"));
        assertEquals("gpt-4.1-mini", r.model());
    }

    @Test
    void interactiveModeIgnoresThePrintAlias() {
        var r = ModelResolver.resolve(ArgParser.parse(new String[] {}),
                                        settings(Map.of("print", "openai/gpt-4.1-mini"), "gpt-5.5"));
        assertEquals("gpt-5.5", r.model());
    }

    @Test
    void undefinedAliasFailsClearly() {
        var ex = assertThrows(IllegalStateException.class, () -> ModelResolver.resolve(
                                        ArgParser.parse(new String[] {"--model", "alias/does-not-exist"}),
                                        settings(Map.of(), "gpt-5.5")));
        assertTrue(ex.getMessage().toLowerCase().contains("alias"), ex.getMessage());
    }

    @Test
    void aConfiguredUserGetsTheSpecificMissingKeyDiagnostic() {
        // Some provider works, another was asked for: name the key that is missing, for that provider.
        String msg = ModelResolver.missingApiKeyMessage("mistral", "mistral-large", settings(Map.of(), "gpt-5.5"));
        assertTrue(msg.contains("mistral"), msg);
        assertTrue(msg.contains("MISTRAL_API_KEY"), msg);
        assertFalse(msg.contains("get started"), "an already-configured user is not onboarded again: " + msg);
    }

    @Test
    void theFirstRunMessageOnboardsInsteadOfDiagnosing() {
        // Shown when nothing is configured at all. Asserted on content only: which branch fires depends on the
        // ambient environment (provider env vars), which this class deliberately does not exercise.
        String msg = ModelResolver.firstRunMessage();
        assertTrue(msg.contains("No provider configured yet"), msg);
        assertTrue(msg.contains("pijota login"), "the sign-in path is offered: " + msg);
        assertTrue(msg.contains("API_KEY"), "the key path is offered too: " + msg);
        assertFalse(msg.contains("gpt-5.5"), "no diagnosis of a default the user never chose: " + msg);
        assertFalse(msg.startsWith("No API key for"), "not phrased as a failure: " + msg);
    }
}
