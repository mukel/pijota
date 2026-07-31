package org.pijota.extensions.mcp;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A bidirectional, line-delimited message channel to a single MCP server. MCP's stdio transport frames each JSON-RPC
 * message as one UTF-8 line, so the channel is simply "send a line / receive a line". Abstracting it (rather than
 * coupling {@link McpClient} to a {@link Process}) keeps the client pure protocol logic and lets tests drive it with an
 * in-memory server.
 */
interface McpTransport extends AutoCloseable {

    /** Sends one framed message. Implementations must be safe to call from multiple threads. */
    void send(String line) throws IOException;

    /** Blocks for the next message from the server; returns {@code null} at end-of-stream (server closed/exited). */
    String receive() throws IOException;

    /** True while the underlying channel is still usable. */
    default boolean alive() {
        return true;
    }

    /** The last few stderr lines the server wrote, for diagnosing failed connects; empty when none or unsupported. */
    default String stderrTail() {
        return "";
    }

    @Override
    void close();

    /** Factory used by the extension to open a transport for a configured server (production: spawns a process). */
    @FunctionalInterface
    interface Factory {
        McpTransport open(McpServerConfig config, Path cwd) throws IOException;
    }
}
