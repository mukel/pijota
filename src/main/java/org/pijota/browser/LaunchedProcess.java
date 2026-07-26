package org.pijota.browser;

import org.pijota.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * A single child process a backend launched — the browser (CDP) or its WebDriver (BiDi). Captures stderr for
 * {@link #exitDiagnostic()}, tree-kills on demand, and installs a JVM-shutdown hook that tree-kills so browser children
 * (renderer/GPU/zygote) aren't orphaned. Shared by {@link CdpBackend} and {@link BiDiBackend}.
 */
final class LaunchedProcess {

    private final AtomicBoolean hookInstalled = new AtomicBoolean();
    private volatile Process process;
    private volatile Path stderrLog;

    /**
     * Starts {@code cmd} (stdout discarded, stderr captured) and installs the one-shot shutdown hook on first launch.
     */
    void start(List<String> cmd) throws IOException {
        deleteLog(stderrLog); // a relaunch (process died) reuses this instance — don't orphan the previous log
        Path log = Files.createTempFile("pijota-browser-", ".log");
        this.stderrLog = log;
        process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log.toFile())
                                        .start();
        if (hookInstalled.compareAndSet(false, true))
            Runtime.getRuntime().addShutdownHook(new Thread(this::kill, "pijota-browser-shutdown"));
    }

    /**
     * Polls {@code ready} until the process comes up, failing fast (with {@link #exitDiagnostic()}) if it exits first.
     * On timeout, throws naming the exit diagnostic when available, else the supplied {@code timeoutHint}. Shared by
     * the CDP and BiDi backends, whose only real difference is the readiness probe.
     */
    void awaitReady(BooleanSupplier probe, long timeoutMs, String label, String timeoutHint)
                                    throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (probe.getAsBoolean())
                return;
            if (!aliveOrAbsent())
                throw new IllegalStateException(label + " failed to start: " + exitDiagnostic());
            Thread.sleep(150);
        }
        String diagnostic = exitDiagnostic();
        String suffix = !diagnostic.isEmpty()
                                        ? " — " + diagnostic
                                        : timeoutHint == null || timeoutHint.isEmpty() ? "" : " (" + timeoutHint + ")";
        throw new IllegalStateException(label + " did not come up within " + timeoutMs / 1000 + "s" + suffix);
    }

    /** True if we never launched one, or the one we launched is still running (the backend's liveness check). */
    boolean aliveOrAbsent() {
        Process p = process;
        return p == null || p.isAlive();
    }

    /** True only if we launched one and it's still running (the relaunch check: launch when this is false). */
    boolean running() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /**
     * If the launched process has already exited, its exit code plus the tail of its captured stderr — so a "did not
     * come up" timeout can name the real cause instead of the symptom. Empty while running or never launched.
     */
    String exitDiagnostic() {
        Process p = process;
        if (p == null || p.isAlive())
            return "";
        String tail = "";
        Path log = stderrLog;
        if (log != null) {
            try {
                var lines = Files.readAllLines(log);
                tail = String.join("\n", lines.subList(Math.max(0, lines.size() - 6), lines.size())).strip();
            } catch (IOException ignored) {
                // best-effort; the exit code alone is still useful
            }
        }
        return "the process exited (code " + p.exitValue() + ")" + (tail.isEmpty() ? "" : "; last output:\n" + tail);
    }

    /** Kills the launched process and its descendants and removes the stderr log; a no-op if nothing was launched. */
    void kill() {
        Process p = process;
        process = null;
        if (p != null)
            ProcessRunner.killTree(p);
        deleteLog(stderrLog);
        stderrLog = null;
    }

    private static void deleteLog(Path log) {
        if (log == null)
            return;
        try {
            Files.deleteIfExists(log);
        } catch (IOException ignored) {
            // temp file; OS will reap it
        }
    }
}
