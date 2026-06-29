package ai.pijota.extensions.jobs;

import ai.pijota.util.ProcessRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Registry of fire-and-forget shell jobs the agent starts via {@code jobs_run} and later reads or kills (via
 * {@code jobs_output} / {@code jobs_kill}). Each job's merged stdout+stderr is drained by a daemon thread into a capped
 * rolling buffer; {@link #read} returns only the slice produced since the previous read, so the model sees incremental
 * progress. Unlike the tmux integration this needs no external binary — plain JDK process management, always available
 * and native-image safe.
 */
public final class JobRegistry {
    private static final int MAX_BUFFER = 200_000; // cap retained output per job (characters, not bytes) to bound
                                                   // memory

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final Consumer<Completion> onComplete;

    public JobRegistry() {
        this(c -> {});
    }

    /** {@code onComplete} fires (off the drain thread) when a job exits on its own — not when it's killed. */
    public JobRegistry(Consumer<Completion> onComplete) {
        this.onComplete = onComplete == null ? c -> {} : onComplete;
        // Cleanup is driven by JobsExtension.onShutdown (the extension lifecycle), not a per-instance JVM shutdown
        // hook — that keeps shutdown ordered/logged by ExtensionRegistry and avoids one dangling hook per instance.
    }

    private static final class Job {
        final String id;
        final String command;
        final String name;
        final Process process;
        final StringBuilder out = new StringBuilder(); // guarded by this
        int readOffset; // guarded by this
        boolean truncated; // guarded by this
        volatile boolean killed; // set before an intentional kill so its exit isn't reported as completion

        Job(String id, String command, String name, Process process) {
            this.id = id;
            this.command = command;
            this.name = name;
            this.process = process;
        }
    }

    /** Launches {@code command} detached and returns its job id; output drains in the background until it exits. */
    public String start(List<String> command, Path workDir) throws IOException {
        return start(command, workDir, null, null);
    }

    /**
     * Launches {@code command} detached with an optional human-readable {@code name} and extra environment variables;
     * output drains in the background until it exits.
     */
    public String start(List<String> command, Path workDir, String name, Map<String, String> env) throws IOException {
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null)
            pb.directory(workDir.toFile());
        if (env != null)
            pb.environment().putAll(env);
        Process process = pb.start();
        String id = "bg_" + seq.incrementAndGet();
        String label = (name != null && !name.isBlank()) ? name : String.join(" ", command);
        var job = new Job(id, String.join(" ", command), label, process);
        jobs.put(id, job);
        var drain = new Thread(() -> drain(job), "bg-job-" + id);
        drain.setDaemon(true);
        drain.start();
        return id;
    }

    private void drain(Job job) {
        char[] buf = new char[4096];
        try (var reader = new BufferedReader(
                                        new InputStreamReader(job.process.getInputStream(), StandardCharsets.UTF_8))) {
            int n;
            while ((n = reader.read(buf)) != -1) {
                synchronized (job) {
                    int room = MAX_BUFFER - job.out.length();
                    if (room > 0)
                        job.out.append(buf, 0, Math.min(n, room));
                    if (n > room)
                        job.truncated = true;
                }
            }
        } catch (IOException ignored) {
            // stream closed because the process ended or was killed — nothing more to drain
        }
        // EOF means the process has exited (or was killed) and all output is buffered: report completion so the agent
        // is notified instead of having to poll. waitFor() yields the exit code; intentional kills are not reported.
        try {
            int code = job.process.waitFor();
            if (!job.killed)
                onComplete.accept(new Completion(job.id, job.command, job.name, code));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Output produced since the previous read for this job plus its status, or {@code null} if the id is unknown. */
    public Read read(String id) {
        Job job = jobs.get(id);
        if (job == null)
            return null;
        String chunk;
        boolean truncated;
        int outSize;
        synchronized (job) {
            chunk = job.out.substring(Math.min(job.readOffset, job.out.length()));
            job.readOffset = job.out.length();
            truncated = job.truncated;
            outSize = job.out.length();
        }
        boolean running = job.process.isAlive();
        Integer exitCode = running ? null : job.process.exitValue();
        return new Read(id, chunk, running, exitCode, truncated, outSize);
    }

    /** Check whether a job is still running without consuming any buffered output. */
    public boolean isRunning(String id) {
        Job job = jobs.get(id);
        return job != null && job.process.isAlive();
    }

    /** Terminates the job (and its child process tree). Returns false if the id is unknown. */
    public boolean kill(String id) {
        Job job = jobs.get(id);
        if (job == null)
            return false;
        job.killed = true;
        ProcessRunner.killTree(job.process);
        return true;
    }

    /**
     * Signals the job's whole process tree (like {@link #kill}, not just the shell wrapper): SIGTERM via
     * {@code destroy()} (graceful — a process that traps it and later exits on its own is still reported complete),
     * SIGKILL via {@code destroyForcibly()} (forced — suppresses the completion notification, same as {@code kill}).
     * Returns false if the job id is unknown or the process is no longer alive.
     */
    public boolean signal(String id, String signal) {
        Job job = jobs.get(id);
        if (job == null || !job.process.isAlive())
            return false;
        String s = signal != null ? signal.toUpperCase() : "SIGTERM";
        switch (s) {
            case "SIGTERM" -> signalTree(job.process, false);
            case "SIGKILL" -> {
                job.killed = true; // a forced kill is intentional — don't report it as a natural completion
                signalTree(job.process, true);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Sends SIGTERM ({@code force=false}) or SIGKILL ({@code force=true}) to the process and all its descendants. */
    private static void signalTree(Process process, boolean force) {
        process.descendants().forEach(h -> {
            if (force)
                h.destroyForcibly();
            else
                h.destroy();
        });
        if (force)
            process.destroyForcibly();
        else
            process.destroy();
    }

    public List<Status> list() {
        var statuses = new ArrayList<Status>();
        for (Job job : jobs.values()) {
            boolean alive = job.process.isAlive();
            int outSize;
            boolean truncated;
            synchronized (job) {
                outSize = job.out.length();
                truncated = job.truncated;
            }
            statuses.add(new Status(job.id, job.command, job.name, alive, alive ? null : job.process.exitValue(),
                                            outSize, truncated));
        }
        return statuses;
    }

    public void killAll() {
        for (Job job : jobs.values())
            if (job.process.isAlive()) {
                job.killed = true;
                ProcessRunner.killTree(job.process);
            }
    }

    public record Read(String id, String output, boolean running, Integer exitCode, boolean truncated,
                                    int outputChars) {
    }

    public record Status(String id, String command, String name, boolean running, Integer exitCode, int outputChars,
                                    boolean truncated) {
    }

    /** A job that finished on its own (not killed): its id, the command, the name, and the process exit code. */
    public record Completion(String id, String command, String name, int exitCode) {
    }
}
