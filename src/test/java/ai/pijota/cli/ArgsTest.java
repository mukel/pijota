package ai.pijota.cli;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgsTest {

    private static Args emptyArgs() {
        return new Args(null, null, null, null, List.of(), null, null, null, false, false, false, false, false, false,
                                        false, List.of(), List.of(), List.of(), List.of(), List.of(), false, false,
                                        false, false, null, null, false, null, false, false, false, false, false,
                                        List.of(), List.of(), Map.of(), null);
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
        var args = new Args("openai", "gpt-4", null, null, List.of(), null, null, null, false, false, false, false,
                                        false, false, false, List.of(), List.of(), List.of(), List.of(), List.of(),
                                        false, false, false, false, null, null, false, null, false, false, false, false,
                                        false, List.of(), List.of(), Map.of(), null);
        assertEquals("openai", args.provider());
        assertEquals("gpt-4", args.model());
        assertFalse(args.noSession());
        assertFalse(args.noTools());
    }

    @Test
    void noSessionAndNoToolsFlagsStored() {
        var args = new Args(null, null, null, null, List.of(), null, null, null, true, true, false, false, false, false,
                                        false, List.of(), List.of(), List.of(), List.of(), List.of(), false, false,
                                        false, false, null, null, false, null, false, false, false, false, false,
                                        List.of(), List.of(), Map.of(), null);
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
        var args = new Args(null, null, null, null, List.of(), null, null, null, false, false, false, false, false,
                                        false, false, List.of(), List.of(), List.of(), List.of(), List.of(), false,
                                        false, false, false, null, null, false, null, false, false, false, false, false,
                                        List.of(), List.of(), Map.of("key", "value"), null);
        assertEquals(Map.of("key", "value"), args.extensionFlags());
    }
}
