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

/**
 * {@link McpTransport} backed by a long-lived child process speaking MCP over stdio: requests are written to the
 * process's stdin, responses are read from its stdout (one JSON message per line). stderr is discarded so a chatty
 * server can't block on a full pipe. On {@link #close()} the whole process tree is killed via
 * {@link ProcessRunner#killTree(Process)}.
 */
final class StdioTransport implements McpTransport {

    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();

    private StdioTransport(Process process) {
        this.process = process;
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
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
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return new StdioTransport(pb.start());
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
