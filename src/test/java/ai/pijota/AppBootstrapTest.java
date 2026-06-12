package ai.pijota;

import static org.junit.jupiter.api.Assertions.*;

import ai.pijota.cli.Args;
import ai.pijota.cli.ArgParser;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AppBootstrapTest {

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
