package ai.pijota.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ProcessRunner {
    private static final Duration KILL_GRACE = Duration.ofMillis(300);
    private static final Duration GOBBLER_JOIN = Duration.ofMinutes(5);

    private final List<String> command;
    private Path workDir;
    private Duration timeout = Duration.ofMinutes(5);
    private boolean noTimeout;
    private Supplier<String> stdinFeeder;
    private Consumer<String> stdoutLineHandler;
    private boolean captureStdout = true;
    private boolean captureStderr = true;
    private boolean inheritIO;
    private int maxOutputBytes = 100 * 1024;
    private Consumer<Process> onStart;
    private boolean redirectErrorStream;
    private boolean discardStderr;

    private ProcessRunner(List<String> command) {
        this.command = List.copyOf(command);
    }

    public static ProcessRunner command(String... args) {
        return new ProcessRunner(List.of(args));
    }

    public static ProcessRunner command(List<String> args) {
        return new ProcessRunner(args);
    }

    public ProcessRunner workDir(Path dir) {
        this.workDir = dir;
        return this;
    }

    public ProcessRunner timeout(long duration, TimeUnit unit) {
        this.timeout = Duration.ofMillis(unit.toMillis(duration));
        return this;
    }

    public ProcessRunner timeout(Duration duration) {
        this.timeout = duration;
        return this;
    }

    public ProcessRunner noTimeout() {
        this.noTimeout = true;
        return this;
    }

    public ProcessRunner maxOutputBytes(int bytes) {
        this.maxOutputBytes = bytes;
        return this;
    }

    public ProcessRunner onStart(Consumer<Process> handler) {
        this.onStart = handler;
        return this;
    }

    public ProcessRunner feedStdin(String text) {
        this.stdinFeeder = () -> text;
        return this;
    }

    public ProcessRunner feedStdin(Supplier<String> feeder) {
        this.stdinFeeder = feeder;
        return this;
    }

    public ProcessRunner consumeStdoutLines(Consumer<String> handler) {
        this.stdoutLineHandler = handler;
        this.captureStdout = false;
        return this;
    }

    public ProcessRunner captureStdout(boolean capture) {
        this.captureStdout = capture;
        return this;
    }

    public ProcessRunner captureStderr(boolean capture) {
        this.captureStderr = capture;
        return this;
    }

    public ProcessRunner inheritIO() {
        this.inheritIO = true;
        this.captureStdout = false;
        this.captureStderr = false;
        return this;
    }

    public ProcessRunner redirectErrorStream(boolean enable) {
        this.redirectErrorStream = enable;
        if (enable)
            this.captureStderr = false;
        return this;
    }

    public ProcessRunner discardStderr() {
        this.discardStderr = true;
        this.captureStderr = false;
        return this;
    }

    // -- execution modes --

    public Result run() throws IOException {
        validate();
        Process process = startProcess();
        setOnStart(process);

        var stdoutCapture = new CaptureBuffer(maxOutputBytes);
        var stderrCapture = new CaptureBuffer(maxOutputBytes);
        var stdoutThreadRef = new AtomicReference<Thread>();
        var stderrThreadRef = new AtomicReference<Thread>();
        var discardThreadRef = new AtomicReference<Thread>();
        var aborted = new AtomicBoolean(false);

        Thread stdinThread = null;
        if (stdinFeeder != null) {
            stdinThread = startStdinFeeder(process, aborted);
        }

        if (stdoutLineHandler != null) {
            stdoutThreadRef.set(startStdoutLineReader(process, aborted));
        } else if (captureStdout) {
            stdoutThreadRef.set(startCaptureReader(process.getInputStream(), stdoutCapture));
        } else {
            closeQuietly(process.getInputStream());
        }

        if (captureStderr) {
            stderrThreadRef.set(startCaptureReader(process.getErrorStream(), stderrCapture));
        } else if (discardStderr) {
            discardThreadRef.set(startDiscardReader(process.getErrorStream()));
        } else {
            closeQuietly(process.getErrorStream());
        }

        // wait for process
        boolean finished = false;
        boolean timedOut = false;
        try {
            if (noTimeout) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished && !aborted.get()) {
                    timedOut = true;
                    killTree(process);
                    finished = process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                    if (!finished)
                        process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            aborted.set(true);
            killTree(process);
            Thread.currentThread().interrupt();
        } finally {
            aborted.set(true);
            if (stdinThread != null)
                stdinThread.interrupt();
            joinThread(stdoutThreadRef.get(), GOBBLER_JOIN);
            joinThread(stderrThreadRef.get(), GOBBLER_JOIN);
            joinThread(discardThreadRef.get(), GOBBLER_JOIN);
            closeQuietly(process.getOutputStream());
            if (process.isAlive()) {
                killTree(process);
                process.destroyForcibly();
            }
        }

        int exitCode = process.isAlive() ? -1 : process.exitValue();
        return new Result(exitCode, stdoutCapture.text(), stderrCapture.text(), timedOut,
                                        stdoutCapture.truncated() || stderrCapture.truncated());
    }

    /** Runs an interactive process that takes over the terminal (editor, pager). Returns exit code. */
    public int runInteractive() throws IOException {
        validate();
        inheritIO = true;
        Process process = startProcess();
        setOnStart(process);
        try {
            int exitCode = process.waitFor();
            return exitCode;
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            if (process.isAlive()) {
                killTree(process);
                process.destroyForcibly();
            }
        }
    }

    /** Runs a process, captures stdout as a string. Convenience for simple commands. */
    public String runCapture() throws IOException {
        maxOutputBytes(maxOutputBytes);
        discardStderr();
        return run().stdout();
    }

    // -- process tree killing (ProcessHandle-based, bullet-proof) --

    public static void killTree(Process process) {
        if (process == null)
            return;
        var handles = new ArrayList<ProcessHandle>();
        process.descendants().forEach(handles::add);
        handles.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        for (var h : handles)
            destroy(h);
        destroy(process.toHandle());
        waitForExit(process.toHandle(), KILL_GRACE);
        for (var h : handles)
            destroyForcibly(h);
        destroyForcibly(process.toHandle());
    }

    public static void abortIfAlive(Process process) {
        if (process != null && process.isAlive())
            killTree(process);
    }

    private static void destroy(ProcessHandle h) {
        try {
            if (h.isAlive())
                h.destroy();
        } catch (Exception ignored) {}
    }

    private static void destroyForcibly(ProcessHandle h) {
        try {
            if (h.isAlive())
                h.destroyForcibly();
        } catch (Exception ignored) {}
    }

    private static void waitForExit(ProcessHandle h, Duration timeout) {
        try {
            h.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
    }

    // -- internal helpers --

    private Process startProcess() throws IOException {
        var builder = new ProcessBuilder(command);
        if (workDir != null)
            builder.directory(workDir.toFile());
        if (inheritIO) {
            builder.inheritIO();
        } else {
            if (redirectErrorStream) {
                builder.redirectErrorStream(true);
            }
        }
        return builder.start();
    }

    private void setOnStart(Process p) {
        if (onStart != null) {
            try {
                onStart.accept(p);
            } catch (Exception ignored) {}
        }
    }

    private Thread startStdinFeeder(Process process, AtomicBoolean aborted) {
        return Thread.ofPlatform().daemon().name("pijota-stdin-feeder").start(() -> {
            try (var out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                if (stdinFeeder != null && !aborted.get()) {
                    out.write(stdinFeeder.get());
                    out.flush();
                }
            } catch (IOException ignored) {}
        });
    }

    private Thread startStdoutLineReader(Process process, AtomicBoolean aborted) {
        return Thread.ofVirtual().name("pijota-stdout-lines").start(() -> {
            try (var reader = new BufferedReader(
                                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        stdoutLineHandler.accept(line);
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
        });
    }

    private Thread startCaptureReader(InputStream stream, CaptureBuffer capture) {
        return Thread.ofPlatform().daemon().name("pijota-stream-capture").start(() -> {
            try (stream) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = stream.read(buf)) != -1) {
                    capture.write(buf, read);
                }
            } catch (IOException ignored) {}
        });
    }

    private Thread startDiscardReader(InputStream stream) {
        return Thread.ofPlatform().daemon().name("pijota-stream-discard").start(() -> {
            try (stream) {
                byte[] buf = new byte[8192];
                while (stream.read(buf) != -1) {}
            } catch (IOException ignored) {}
        });
    }

    private void validate() {
        if (command.isEmpty())
            throw new IllegalArgumentException("command is required");
        for (String arg : command) {
            if (arg == null)
                throw new IllegalArgumentException("command contains null argument");
        }
        if (!noTimeout && (timeout.isZero() || timeout.isNegative()))
            throw new IllegalArgumentException("positive timeout is required");
    }

    private static void joinThread(Thread t, Duration timeout) {
        if (t == null)
            return;
        try {
            t.join(timeout.toMillis());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null)
                c.close();
        } catch (IOException ignored) {}
    }

    // -- result --

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean outputTruncated) {
        public boolean success() {
            return exitCode == 0 && !timedOut;
        }
    }

    // -- capture buffer (thread-safe, bounded) --

    private static final class CaptureBuffer {
        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean truncated;

        CaptureBuffer(int maxBytes) {
            this.maxBytes = Math.max(4096, maxBytes);
        }

        synchronized void write(byte[] buf, int len) {
            int remaining = maxBytes - bytes.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            bytes.write(buf, 0, Math.min(len, remaining));
            if (len > remaining)
                truncated = true;
        }

        synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
    }
}
