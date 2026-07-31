package org.pijota.extensions.mcp;

import org.pijota.util.ProcessRunner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * {@link McpTransport} backed by a long-lived child process speaking MCP over stdio: requests are written to the
 * process's stdin, responses are read from its stdout (one JSON message per line). stderr is drained continuously by a
 * daemon thread (so a chatty server can't block on a full pipe) into a small tail buffer, because a failing server
 * explains itself there and "disconnected" alone is undebuggable (e.g. npx printing a 404 for a mistyped package). On
 * {@link #close()} the whole process tree is killed via {@link ProcessRunner#killTree(Process)}.
 */
final class StdioTransport implements McpTransport {

    private static final int STDERR_KEEP_LINES = 3; // kept at each end: head (launch failures) + tail (later deaths)

    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    // Launch failures explain themselves in the FIRST stderr lines (npm 404, missing binary); a server dying
    // mid-session explains itself in the LAST ones. Keep both ends, drop the middle.
    private final Deque<String> stderrHead = new ConcurrentLinkedDeque<>();
    private final Deque<String> stderrLast = new ConcurrentLinkedDeque<>();

    private StdioTransport(Process process) {
        this.process = process;
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        var drain = new Thread(() -> {
            try (var err = new BufferedReader(
                                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    if (line.isBlank())
                        continue;
                    if (stderrHead.size() < STDERR_KEEP_LINES) {
                        stderrHead.addLast(line);
                    } else {
                        stderrLast.addLast(line);
                        while (stderrLast.size() > STDERR_KEEP_LINES)
                            stderrLast.pollFirst();
                    }
                }
            } catch (IOException ignored) {
                // the process died or was killed; whatever was captured is the tail
            }
        }, "mcp-stderr-drain");
        drain.setDaemon(true);
        drain.start();
    }

    /** Spawns {@code config.command()} (+ args/env) in {@code cwd} and connects over its stdio. */
    static StdioTransport open(McpServerConfig config, Path cwd) throws IOException {
        var command = new ArrayList<String>();
        command.add(config.command());
        command.addAll(config.args());
        var pb = new ProcessBuilder(command);
        if (cwd != null)
            pb.directory(cwd.toFile());
        pb.environment().putAll(config.env());
        return new StdioTransport(pb.start());
    }

    @Override
    public String stderrTail() {
        var parts = new ArrayList<>(stderrHead);
        if (!stderrLast.isEmpty()) {
            parts.add("…");
            parts.addAll(stderrLast);
        }
        return String.join(" | ", parts);
    }

    @Override
    public void send(String line) throws IOException {
        synchronized (writeLock) {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }
    }

    @Override
    public String receive() throws IOException {
        return reader.readLine();
    }

    @Override
    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        ProcessRunner.killTree(process);
        try {
            writer.close();
        } catch (IOException ignored) {}
        try {
            reader.close();
        } catch (IOException ignored) {}
    }
}
