package org.pijota.cli;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgsTest {

    private static Args emptyArgs() {
        return ArgParser.parse(new String[0]);
    }

    @Test
    void outputModeEnumHasTextJsonRpc() {
        assertEquals(3, Args.OutputMode.values().length);
        assertNotNull(Args.OutputMode.valueOf("TEXT"));
        assertNotNull(Args.OutputMode.valueOf("JSON"));
        assertNotNull(Args.OutputMode.valueOf("RPC"));
    }

    @Test
    void defaultOutputModeIsText() {
        assertEquals(Args.OutputMode.TEXT, Args.OutputMode.valueOf("TEXT"));
    }

    @Test
    void recordFieldsAreAccessible() {
        var args = ArgParser.parse(new String[] {"--provider", "openai", "--model", "gpt-4"});
        assertEquals("openai", args.provider());
        assertEquals("gpt-4", args.model());
        assertFalse(args.noSession());
        assertFalse(args.noTools());
    }

    @Test
    void noSessionAndNoToolsFlagsStored() {
        var args = ArgParser.parse(new String[] {"--no-session", "--no-tools"});
        assertTrue(args.noSession());
        assertTrue(args.noTools());
    }

    @Test
    void listsAreEmptyByDefault() {
        var args = emptyArgs();
        assertTrue(args.appendSystemPrompts().isEmpty());
        assertTrue(args.enabledTools().isEmpty());
        assertTrue(args.extensionPaths().isEmpty());
        assertTrue(args.fileArgs().isEmpty());
        assertTrue(args.promptArgs().isEmpty());
    }

    @Test
    void extensionFlagsStoreStringMap() {
        var args = ArgParser.parse(new String[] {"--key", "value"});
        assertEquals(Map.of("key", "value"), args.extensionFlags());
    }
}
