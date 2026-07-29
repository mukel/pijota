package org.pijota;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.agent.SessionManager;
import org.pijota.cli.Args;
import org.pijota.cli.ArgParser;
import org.pijota.config.AppConfig;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;
import org.pijota.extensions.testkit.ExtensionJars;
import org.pijota.llm.ThinkingLevel;
import org.pijota.messages.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AppBootstrapTest {

    private static SettingsManager settings(Path tmp, Map<String, String> aliases, String defaultModel) {
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", defaultModel)
                                        .withSessionDir(tmp.toString()).toBuilder().aliases(aliases).build();
        return new SettingsManager(config, ignored -> {});
    }

    @Test
    void noToolsActivatesNothing() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        var result = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--no-tools", "--api-key", "sk-test"}),
                                        settings);
        // The tool policy registers everything but activates none — --no-tools disables all tools, bash included.
        assertTrue(result.tools().activeTools().isEmpty(),
                                        "--no-tools activates no tools: " + result.tools().activeTools());
    }

    @Test
    void noBuiltinExtensionsDropsShippedExtensionsButKeepsCoreTools() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        var result = AppBootstrap.bootstrap(ArgParser.parse(
                                        new String[] {"--no-builtin-extensions", "--api-key", "sk-test"}), settings);
        var active = result.tools().activeTools().stream().map(t -> t.name()).toList();
        assertTrue(active.contains("read"), "built-in TOOLS stay (separate axis from extensions): " + active);
        assertFalse(active.contains("task"), "shipped-extension tools go with -nbe: " + active);
        assertFalse(active.contains("goal_set"), active.toString());
    }

    @Test
    void noExtensionsDropsAllExtensionToolsButNotCoreTools() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        var result = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--no-extensions", "--api-key", "sk-test"}),
                                        settings);
        var active = result.tools().activeTools().stream().map(t -> t.name()).toList();
        assertTrue(active.contains("read"), active.toString());
        assertFalse(active.contains("task"), "-ne drops shipped extensions too: " + active);
    }

    @Test
    void backgroundingIsGatedOnTheJobsExtension() throws Exception {
        // The shared job registry reaches the frontends (enabling ctrl+b ctrl+b) only when jobs is active — so a
        // backgrounded command always has jobs_output/jobs_kill to observe and stop it.
        Path tmp = Files.createTempDirectory("bootstrap-test");
        assertNotNull(AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--api-key", "sk-test"}),
                                        settings(tmp, Map.of(), "gpt-5.5")).jobs(),
                                        "jobs on by default → registry wired");
        assertNull(AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--no-jobs", "--api-key", "sk-test"}),
                                        settings(tmp, Map.of(), "gpt-5.5")).jobs(),
                                        "--no-jobs → no registry, no gesture");
        assertNull(AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--no-extensions", "--api-key", "sk-test"}),
                                        settings(tmp, Map.of(), "gpt-5.5")).jobs(),
                                        "--no-extensions → jobs never registered");
    }

    @Test
    void variousFeatureFlagsStillProduceAWiredSession() throws Exception {
        for (String[] flags : new String[][] {{"--no-extensions"}, {"--no-skills"}, {"--no-prompt-templates"},
                {"--no-context-files"}, {"--no-session"}, {"--append-system-prompt", "EXTRA RULES"},
                {"--system-prompt", "CUSTOM"}, {"--tools", "read,bash"}, {"--continue"}, {"--recover"},
                {"--no-transcendence"}}) {
            Path tmp = Files.createTempDirectory("bootstrap-test");
            var args = new ArrayList<>(List.of("--api-key", "sk-test"));
            args.addAll(List.of(flags));
            var result = AppBootstrap.bootstrap(ArgParser.parse(args.toArray(new String[0])),
                                            settings(tmp, Map.of(), "gpt-5.5"));
            assertNotNull(result.session(), "session wired for flags " + Arrays.toString(flags));
            assertNotNull(result.extensions());
            assertNotNull(result.llm());
        }
    }

    @Test
    void enabledToolsRestrictsToolsToTheAllowlist() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var result = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--tools", "read", "--api-key", "sk-test"}),
                                        settings(tmp, Map.of(), "gpt-5.5"));
        assertTrue(result.tools().get("read").isPresent());
    }

    @Test
    void continueLoadsTheMostRecentSessionForTheCwd() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        // pre-create a session for the current cwd in the session dir, so --continue resolves + loads it
        var sm = new SessionManager(settings.sessionDir());
        String cwd = System.getProperty("user.dir");
        var file = sm.createSession(cwd);
        sm.saveMessages(file, List.of(new Message.User("a prior question"),
                                        new Message.Assistant("a prior answer", List.of(), "")), cwd);

        var result = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--continue", "--api-key", "sk-test"}),
                                        settings);

        assertTrue(result.session().messages().stream().anyMatch(
                                        m -> m instanceof Message.User u && u.content().contains("a prior question")),
                                        "the prior session is continued");
    }

    @Test
    void whoamiFlagRegistersTheWhoamiTool() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        var result = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--whoami", "--api-key", "sk-test"}),
                                        settings);
        assertTrue(result.tools().get("whoami").isPresent(), "--whoami opts the tool in");
    }

    @Test
    void explicitExtensionPathIsLoadedDuringBootstrap() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        Path jar = ExtensionJars.serviceLoaderJar(tmp, "org.pijota.extensions.testkit.PathLoadedExtension");

        var result = AppBootstrap.bootstrap(ArgParser
                                        .parse(new String[] {"--extension", jar.toString(), "--api-key", "sk-test"}),
                                        settings(tmp, Map.of(), "gpt-5.5"));

        assertTrue(result.extensions().slashCommands().stream().anyMatch(c -> c.name().equals("path-loaded")));
    }

    @Test
    void explicitExtensionPathHonorsCliEnablementFlagsDuringBootstrap() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        Path jar = ExtensionJars.serviceLoaderJar(tmp, "org.pijota.extensions.testkit.DefaultOffPathLoadedExtension");
        var settings = settings(tmp, Map.of(), "gpt-5.5");

        var disabled = AppBootstrap.bootstrap(ArgParser
                                        .parse(new String[] {"--extension", jar.toString(), "--api-key", "sk-test"}),
                                        settings);
        assertFalse(disabled.extensions().slashCommands().stream().anyMatch(c -> c.name().equals("path-default-off")));

        var enabled = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--extension", jar.toString(),
                "--path-default-off", "--api-key", "sk-test"}), settings);
        assertTrue(enabled.extensions().slashCommands().stream().anyMatch(c -> c.name().equals("path-default-off")));
    }

    @Test
    void transcendencePromptGuidanceFollowsExtensionEnablement() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");

        var defaultResult = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--api-key", "sk-test"}), settings);
        assertFalse(systemPrompt(defaultResult).contains("TRANSCENDENCE:"),
                                        "default sessions should not advertise disabled self-modification");
        assertFalse(defaultResult.tools().get("transcendence").isPresent());

        var enabledResult = AppBootstrap.bootstrap(
                                        ArgParser.parse(new String[] {"--transcendence", "--api-key", "sk-test"}),
                                        settings);
        assertTrue(systemPrompt(enabledResult).contains("TRANSCENDENCE:"));
        assertTrue(enabledResult.tools().get("transcendence").isPresent());

        var noExtensionsResult = AppBootstrap.bootstrap(
                                        ArgParser.parse(new String[] {"--no-extensions", "--api-key", "sk-test"}),
                                        settings);
        assertFalse(systemPrompt(noExtensionsResult).contains("TRANSCENDENCE:"));
    }

    @Test
    void printModeResolvesThePrintAliasModel() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of("print", "openai/gpt-4.1-mini"), "gpt-5.5");
        Args args = ArgParser.parse(new String[] {"--print", "hi", "--api-key", "sk-test"});

        var result = AppBootstrap.bootstrap(args, settings);

        assertEquals("gpt-4.1-mini", result.llm().model(), "print/-p uses the 'print' alias when no --model is given");
    }

    @Test
    void printModeWithoutPrintAliasKeepsDefaultModel() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        Args args = ArgParser.parse(new String[] {"--print", "hi", "--api-key", "sk-test"});

        assertEquals("gpt-5.5", AppBootstrap.bootstrap(args, settings).llm().model());
    }

    @Test
    void interactiveModeIgnoresThePrintAlias() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of("print", "openai/gpt-4.1-mini"), "gpt-5.5");
        Args args = ArgParser.parse(new String[] {"--api-key", "sk-test"}); // no --print

        assertEquals("gpt-5.5", AppBootstrap.bootstrap(args, settings).llm().model());
    }

    @Test
    void explicitModelOverridesThePrintAlias() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of("print", "openai/gpt-4.1-mini"), "gpt-5.5");
        Args args = ArgParser.parse(new String[] {"--print", "hi", "--model", "openai/gpt-5.5", "--api-key",
                "sk-test"});

        assertEquals("gpt-5.5", AppBootstrap.bootstrap(args, settings).llm().model());
    }

    @Test
    void missingApiKeyErrorIncludesProviderModelAndNextSteps() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var provider = new ProviderCatalog.Provider("missing-test", "Missing Test", "openai",
                                        "https://example.invalid/v1", List.of("PIJOTA_MISSING_TEST_API_KEY"),
                                        "test-model", Map.of(), null, null);
        var config = new AppConfig().withDefaultProviderModel("missing-test", "test-model")
                                        .withSessionDir(tmp.toString()).toBuilder().providers(List.of(provider))
                                        .build();
        var settings = new SettingsManager(config, ignored -> {});

        var ex = assertThrows(IllegalStateException.class, () -> AppBootstrap.bootstrap(
                                        ArgParser.parse(new String[] {"--provider", "missing-test"}), settings));

        assertTrue(ex.getMessage().contains("missing-test/test-model"));
        assertTrue(ex.getMessage().contains("PIJOTA_MISSING_TEST_API_KEY"));
        assertTrue(ex.getMessage().contains("--list-models missing-test"));
        assertTrue(ex.getMessage().contains("local/OpenAI-compatible server"));
        assertFalse(ex.getMessage().contains("pijota login missing-test"),
                                        "non-OAuth providers should not suggest login");
    }

    @Test
    void missingOauthProviderKeySuggestsLogin() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var oauth = new ProviderCatalog.OAuth("https://device.example", "https://token.example",
                                        "https://oauth.example", "client", "scope");
        var provider = new ProviderCatalog.Provider("oauth-test", "OAuth Test", "openai", "https://example.invalid/v1",
                                        List.of(), "oauth-model", Map.of(), oauth, null);
        var config = new AppConfig().withDefaultProviderModel("oauth-test", "oauth-model")
                                        .withSessionDir(tmp.toString()).toBuilder().providers(List.of(provider))
                                        .build();
        var settings = new SettingsManager(config, ignored -> {});

        var ex = assertThrows(IllegalStateException.class, () -> AppBootstrap.bootstrap(
                                        ArgParser.parse(new String[] {"--provider", "oauth-test"}), settings));

        assertTrue(ex.getMessage().contains("oauth-test/oauth-model"));
        assertTrue(ex.getMessage().contains("pijota login oauth-test"));
        assertTrue(ex.getMessage().contains("--list-models oauth-test"));
    }

    @Test
    void missingApiKeyDoesNotBlockHeadlessAgentBootstrap() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var provider = new ProviderCatalog.Provider("agent-missing-test", "Agent Missing Test", "openai",
                                        "https://example.invalid/v1", List.of("PIJOTA_AGENT_MISSING_TEST_API_KEY"),
                                        "test-model", Map.of(), null, null);
        var config = new AppConfig().withDefaultProviderModel("agent-missing-test", "test-model")
                                        .withSessionDir(tmp.toString()).toBuilder().providers(List.of(provider))
                                        .build();
        var settings = new SettingsManager(config, ignored -> {});

        var result = AppBootstrap.bootstrap(
                                        ArgParser.parse(new String[] {"--agent", "--provider", "agent-missing-test"}),
                                        settings);

        assertEquals("agent-missing-test", result.llm().provider());
        assertEquals("test-model", result.llm().model());
    }

    @Test
    void missingApiKeyMessageUsesExplicitProviderAndModel() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var provider = new ProviderCatalog.Provider("explicit-missing-test", "Explicit Missing Test", "openai",
                                        "https://example.invalid/v1", List.of("PIJOTA_EXPLICIT_MISSING_TEST_API_KEY"),
                                        "default-model", Map.of(), null, null);
        var config = new AppConfig().withDefaultProviderModel("explicit-missing-test", "default-model")
                                        .withSessionDir(tmp.toString()).toBuilder().providers(List.of(provider))
                                        .build();
        var settings = new SettingsManager(config, ignored -> {});

        var ex = assertThrows(IllegalStateException.class, () -> AppBootstrap.bootstrap(
                                        ArgParser.parse(new String[] {"--model", "explicit-missing-test/custom-model"}),
                                        settings));

        assertTrue(ex.getMessage().contains("explicit-missing-test/custom-model"));
        assertTrue(ex.getMessage().contains("PIJOTA_EXPLICIT_MISSING_TEST_API_KEY"));
    }

    @Test
    void bootstrapAgentModeReturnsWiredResult() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", "gpt-4o")
                                        .withSessionDir(tmp.toString());
        var settings = new SettingsManager(config, ignored -> {});
        Args args = ArgParser.parse(new String[] {"--agent", "--api-key", "sk-test"});

        var result = AppBootstrap.bootstrap(args, settings);

        assertNotNull(result.llm());
        assertNotNull(result.tools());
        assertNotNull(result.extensions());
        assertNotNull(result.session());
        assertNotNull(result.sessions());
        assertSame(settings, result.settings());
        assertSame(args, result.args());
        assertNotNull(result.cwd());
    }

    private static String systemPrompt(AppBootstrap.Result result) {
        return result.session().messages().stream().filter(m -> m instanceof Message.System)
                                        .map(m -> (Message.System) m).findFirst().orElseThrow().content();
    }

    @Test
    void bootstrapPrintModeReturnsWiredResult() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", "gpt-4o")
                                        .withSessionDir(tmp.toString());
        var settings = new SettingsManager(config, ignored -> {});
        Args args = ArgParser.parse(new String[] {"--print", "hello", "--api-key", "sk-test"});

        var result = AppBootstrap.bootstrap(args, settings);

        assertNotNull(result.llm());
        assertNotNull(result.session());
        assertTrue(result.args().print());
    }

    @Test
    void bootstrapAppliesThinkingLevel() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", "gpt-4o")
                                        .withThinkingLevel("low").withSessionDir(tmp.toString());
        var settings = new SettingsManager(config, ignored -> {});
        Args args = ArgParser.parse(new String[] {"--agent", "--api-key", "sk-test"});

        var result = AppBootstrap.bootstrap(args, settings);

        assertEquals(ThinkingLevel.LOW, result.llm().thinkingLevel());
    }

    @Test
    void bootstrapArgsThinkingLevelOverridesSettings() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", "gpt-4o")
                                        .withThinkingLevel("off").withSessionDir(tmp.toString());
        var settings = new SettingsManager(config, ignored -> {});
        Args args = ArgParser.parse(new String[] {"--agent", "--api-key", "sk-test", "--thinking", "high"});

        var result = AppBootstrap.bootstrap(args, settings);

        assertEquals(ThinkingLevel.HIGH, result.llm().thinkingLevel());
    }
}
