package ai.pijota.browser;

import ai.pijota.util.ProcessRunner;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single child process a backend launched — the browser (CDP) or its WebDriver (BiDi). Discards the child's output,
 * installs a one-shot JVM-shutdown hook that kills the whole tree (so renderer/GPU/zygote or driver-spawned browser
 * children aren't orphaned on SIGTERM), and tree-kills on demand. Shared by {@link CdpBackend} and {@link BiDiBackend}.
 */
final class LaunchedProcess {

    private final AtomicBoolean hookInstalled = new AtomicBoolean();
    private volatile Process process;

    /** Starts {@code cmd} (stdout/stderr discarded) and installs the one-shot shutdown hook on first launch. */
    void start(List<String> cmd) throws IOException {
        process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (hookInstalled.compareAndSet(false, true))
            Runtime.getRuntime().addShutdownHook(new Thread(this::kill, "pijota-browser-shutdown"));
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

    /** Kills the launched process and its descendants; a no-op if nothing was launched. */
    void kill() {
        Process p = process;
        process = null;
        if (p != null)
            ProcessRunner.killTree(p);
    }
}
