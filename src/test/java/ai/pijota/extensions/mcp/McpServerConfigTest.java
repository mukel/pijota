package ai.pijota.extensions.mcp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConfigTest {

    @Test
    void nullCollectionsBecomeEmpty() {
        var cfg = new McpServerConfig("srv", "cmd", null, null, true);
        assertEquals(List.of(), cfg.args());
        assertEquals(Map.of(), cfg.env());
    }

    @Test
    void collectionsAreDefensivelyCopiedAndImmutable() {
        var args = new java.util.ArrayList<>(List.of("a"));
        var env = new java.util.HashMap<>(Map.of("K", "V"));
        var cfg = new McpServerConfig("srv", "cmd", args, env, true);

        args.add("b");
        env.put("K2", "V2");
        assertEquals(List.of("a"), cfg.args(), "mutating the source list must not affect the config");
        assertEquals(Map.of("K", "V"), cfg.env(), "mutating the source map must not affect the config");
        assertThrows(UnsupportedOperationException.class, () -> cfg.args().add("c"));
    }
}
