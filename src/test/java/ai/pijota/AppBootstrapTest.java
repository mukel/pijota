package ai.pijota;

import static org.junit.jupiter.api.Assertions.*;

import ai.pijota.cli.Args;
import ai.pijota.cli.ArgParser;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AppBootstrapTest {

    private static SettingsManager settings(Path tmp, Map<String, String> aliases, String defaultModel) {
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", defaultModel)
                                        .withSessionDir(tmp.toString()).toBuilder().aliases(aliases).build();
        return new SettingsManager(config, _ -> {});
    }

    @Test
    void noToolsLeavesOnlyTheBashTool() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        var result = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--no-tools", "--api-key", "sk-test"}),
                                        settings);
        assertTrue(result.tools().get("bash").isPresent(), "--no-tools still keeps bash");
        assertTrue(result.tools().get("read").isEmpty(), "--no-tools drops the default toolset");
    }

    @Test
    void variousFeatureFlagsStillProduceAWiredSession() throws Exception {
        for (String[] flags : new String[][] {{"--no-extensions"}, {"--no-skills"}, {"--no-prompt-templates"},
                {"--no-context-files"}, {"--no-session"}, {"--append-system-prompt", "EXTRA RULES"},
                {"--system-prompt", "CUSTOM"}, {"--tools", "read,bash"}, {"--continue"}, {"--recover"},
                {"--no-transcendence"}}) {
            Path tmp = Files.createTempDirectory("bootstrap-test");
            var args = new java.util.ArrayList<>(java.util.List.of("--api-key", "sk-test"));
            args.addAll(java.util.List.of(flags));
            var result = AppBootstrap.bootstrap(ArgParser.parse(args.toArray(new String[0])),
                                            settings(tmp, Map.of(), "gpt-5.5"));
            assertNotNull(result.session(), "session wired for flags " + java.util.Arrays.toString(flags));
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
    void whoamiFlagRegistersTheWhoamiTool() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var settings = settings(tmp, Map.of(), "gpt-5.5");
        var result = AppBootstrap.bootstrap(ArgParser.parse(new String[] {"--whoami", "--api-key", "sk-test"}),
                                        settings);
        assertTrue(result.tools().get("whoami").isPresent(), "--whoami opts the tool in");
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
    void bootstrapAgentModeReturnsWiredResult() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", "gpt-4o")
                                        .withSessionDir(tmp.toString());
        var settings = new SettingsManager(config, _ -> {});
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

    @Test
    void bootstrapPrintModeReturnsWiredResult() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", "gpt-4o")
                                        .withSessionDir(tmp.toString());
        var settings = new SettingsManager(config, _ -> {});
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
        var settings = new SettingsManager(config, _ -> {});
        Args args = ArgParser.parse(new String[] {"--agent", "--api-key", "sk-test"});

        var result = AppBootstrap.bootstrap(args, settings);

        assertEquals(ai.pijota.llm.ThinkingLevel.LOW, result.llm().thinkingLevel());
    }

    @Test
    void bootstrapArgsThinkingLevelOverridesSettings() throws Exception {
        Path tmp = Files.createTempDirectory("bootstrap-test");
        var config = new AppConfig().withApiKey("openai", "sk-test").withDefaultProviderModel("openai", "gpt-4o")
                                        .withThinkingLevel("off").withSessionDir(tmp.toString());
        var settings = new SettingsManager(config, _ -> {});
        Args args = ArgParser.parse(new String[] {"--agent", "--api-key", "sk-test", "--thinking", "high"});

        var result = AppBootstrap.bootstrap(args, settings);

        assertEquals(ai.pijota.llm.ThinkingLevel.HIGH, result.llm().thinkingLevel());
    }
}
