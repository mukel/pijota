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
}
