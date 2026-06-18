package ai.pijota.extensions.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link StdioTransport} against real, ubiquitous Unix processes (no MCP server, no network): {@code cat}
 * echoes a line back (send/receive/alive), and {@code true} exits immediately (receive→null at EOF). Skipped on
 * Windows.
 */
@Tag("integration")
class StdioTransportTest {

    private static final boolean UNIX = !System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Path TMP = Path.of(System.getProperty("java.io.tmpdir"));

    @Test
    void roundTripsALineThroughCatAndStaysAlive() throws Exception {
        assumeTrue(UNIX, "needs a Unix-like `cat`");
        var transport = StdioTransport.open(new McpServerConfig("cat", "cat", List.of(), Map.of(), true), TMP);
        try {
            assertTrue(transport.alive());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                transport.send("{\"jsonrpc\":\"2.0\",\"id\":1}");
                assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1}", transport.receive());
            });
        } finally {
            transport.close();
        }
    }

    @Test
    void receiveReturnsNullWhenTheProcessExits() throws Exception {
        assumeTrue(UNIX, "needs a Unix-like `true`");
        var transport = StdioTransport.open(new McpServerConfig("true", "true", List.of(), Map.of(), true), TMP);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertNull(transport.receive(),
                                            "stdout EOF after the process exits should surface as null"));
        } finally {
            transport.close();
        }
    }

    @Test
    void closeKillsTheProcess() throws Exception {
        assumeTrue(UNIX, "needs a Unix-like `cat`");
        var transport = StdioTransport.open(new McpServerConfig("cat", "cat", List.of(), Map.of(), true), TMP);
        assertTrue(transport.alive());
        transport.close();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (transport.alive())
                Thread.sleep(10);
        });
        assertFalse(transport.alive());
    }
}
