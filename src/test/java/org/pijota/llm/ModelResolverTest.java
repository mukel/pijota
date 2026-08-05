package org.pijota.llm;

import org.pijota.cli.ArgParser;
import org.pijota.config.AppConfig;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;
import org.pijota.util.Cli;
import org.pijota.util.Util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * The first screen pijota shows anyone has to fit the terminal it is shown on.
     *
     * <p>
     * It was folded at 96, which is not a width any terminal is, so on the classic 80 it broke in the middle of five
     * variable names and of the closing sentence -- {@code FIREWORKS_API_K/EY}, {@code browse/r instead}. A reader
     * scanning for the one variable they have was reading half of it on each of two rows.
     */
    @Test
    void theFirstRunMessageFitsTheTerminalItIsShownOn() {
        // The literal, not Util.terminalWidth(): comparing the message against the same call that shaped it passes for
        // any width whatsoever, including the 96 this is here to rule out. A shell does not export COLUMNS, so the
        // test JVM sees the fallback, and this fixture check says so out loud if that ever stops being true.
        assertEquals(80, Util.terminalWidth(), "fixture: the test JVM should see the fallback width");
        for (String line : ModelResolver.firstRunMessage().lines().toList())
            assertTrue(line.length() <= 80, () -> "a line is " + line.length()
                                            + " columns, so an 80 column terminal folds it mid-word: [" + line + "]");
    }

    /**
     * The first thing a user with no credentials ever sees. It used to lead with `pijota login openai-codex`, which
     * needs a ChatGPT subscription -- presenting the narrow path first and leaving most people to discover that for
     * themselves. An API key works for every provider, so it leads.
     */
    @Test
    void theFirstRunMessageLeadsWithAnApiKeyNotASubscription() {
        String msg = ModelResolver.firstRunMessage();

        int export = msg.indexOf("export ");
        int login = msg.indexOf(Cli.name() + " login");
        assertTrue(export >= 0, "no API-key route offered:\n" + msg);
        assertTrue(login < 0 || export < login, "the subscription route is offered first:\n" + msg);
        assertTrue(login < 0 || msg.contains("subscription"), "the sign-in must say what it needs:\n" + msg);
    }

    /**
     * The reader is scanning for the provider they actually have, so every variable is listed. "and 16 more" is the one
     * thing that cannot help here. And the path must be the home in force -- PIJOTA_HOME and -Dpijota.home both move
     * it, and a message naming ~/.pijota then points at a directory pijota is not using.
     */
    @Test
    void theFirstRunMessageListsEveryVariableAndTheHomeInForce(@TempDir Path home) {
        String previous = System.getProperty("pijota.home");
        System.setProperty("pijota.home", home.toString());
        try {
            String msg = ModelResolver.firstRunMessage();
            var known = ProviderCatalog.providers().stream().flatMap(p -> p.env().stream()).distinct().toList();

            var absent = known.stream().filter(e -> !msg.contains(e)).toList();
            assertTrue(absent.isEmpty(), "variables the reader could be looking for are missing: " + absent);
            assertTrue(msg.contains(home.toString()), "names a home pijota is not using:\n" + msg);
            assertFalse(msg.contains("~/.pijota"), "hardcoded home survives an override:\n" + msg);
        } finally {
            if (previous == null)
                System.clearProperty("pijota.home");
            else
                System.setProperty("pijota.home", previous);
        }
    }

    /** The variable names are read from the catalog; a hardcoded handful listed a quarter of them and could drift. */
    @Test
    void theFirstRunMessageNamesRealEnvironmentVariables() {
        String msg = ModelResolver.firstRunMessage();

        var known = ProviderCatalog.providers().stream().flatMap(p -> p.env().stream()).toList();
        assertFalse(known.isEmpty(), "no provider declares an env var; the fixture is wrong");
        assertTrue(known.stream().anyMatch(msg::contains), "names none of the real variables:\n" + msg);
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

    /**
     * An unknown provider used to reach the LLM client anyway, where the missing base URL defaulted to OpenAI's, so `-m
     * nonexistent/model` with a DeepSeek key failed with an OpenAI error pointing at platform.openai.com. The message
     * named a provider the user had never mentioned.
     */
    @Test
    void anUnknownProviderFailsInsteadOfBecomingOpenAi() {
        var ex = assertThrows(IllegalStateException.class, () -> ModelResolver.resolve(
                                        ArgParser.parse(new String[] {"--model", "nonexistent/some-model"}),
                                        settings(Map.of(), "gpt-5.5")));
        assertTrue(ex.getMessage().contains("nonexistent"), "it must name what was not found: " + ex.getMessage());
        assertFalse(ex.getMessage().toLowerCase().contains("openai.com"),
                                        "and must not send them to a provider they never asked for: "
                                                                        + ex.getMessage());
        assertTrue(ex.getMessage().contains("--list-models"), "with a way to see the real ones: " + ex.getMessage());
    }

    /** A real provider with an off-catalog model still passes through: local fine-tunes and new ids must reach it. */
    @Test
    void aKnownProviderWithAnUnknownModelIsStillHonoured() {
        var r = ModelResolver.resolve(ArgParser.parse(new String[] {"--model", "openai/some-brand-new-id"}),
                                        settings(Map.of(), "gpt-5.5"));
        assertEquals("openai", r.provider());
        assertEquals("some-brand-new-id", r.model());
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
        assertTrue(msg.contains(Cli.name() + " login"), "the sign-in path is offered: " + msg);
        assertTrue(msg.contains("API_KEY"), "the key path is offered too: " + msg);
        assertFalse(msg.contains("gpt-5.5"), "no diagnosis of a default the user never chose: " + msg);
        assertFalse(msg.startsWith("No API key for"), "not phrased as a failure: " + msg);
    }
}
