package ai.pijota.extensions.background;

import ai.pijota.util.ProcessRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Registry of fire-and-forget shell jobs the agent starts via {@code bash_background} and later reads or kills (via
 * {@code bash_output} / {@code bash_kill}). Each job's merged stdout+stderr is drained by a daemon thread into a capped
 * rolling buffer; {@link #read} returns only the slice produced since the previous read, so the model sees incremental
 * progress. Unlike the tmux integration this needs no external binary — plain JDK process management, always available
 * and native-image safe.
 */
public final class BackgroundJobs {
    private static final int MAX_BUFFER = 200_000; // cap retained output per job to bound memory

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final Consumer<Completion> onComplete;

    public BackgroundJobs() {
        this(c -> {});
    }

    /** {@code onComplete} fires (off the drain thread) when a job exits on its own — not when it's killed. */
    public BackgroundJobs(Consumer<Completion> onComplete) {
        this.onComplete = onComplete == null ? c -> {} : onComplete;
        Runtime.getRuntime().addShutdownHook(new Thread(this::killAll, "bg-jobs-shutdown"));
    }

    private static final class Job {
        final String id;
        final String command;
        final Process process;
        final StringBuilder out = new StringBuilder(); // guarded by this
        int readOffset; // guarded by this
        boolean truncated; // guarded by this
        volatile boolean killed; // set before an intentional kill so its exit isn't reported as completion

        Job(String id, String command, Process process) {
            this.id = id;
            this.command = command;
            this.process = process;
        }
    }

    /** Launches {@code command} detached and returns its job id; output drains in the background until it exits. */
    public String start(List<String> command, Path workDir) throws IOException {
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null)
            pb.directory(workDir.toFile());
        Process process = pb.start();
        String id = "bg_" + seq.incrementAndGet();
        var job = new Job(id, String.join(" ", command), process);
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
                onComplete.accept(new Completion(job.id, job.command, code));
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
        synchronized (job) {
            chunk = job.out.substring(Math.min(job.readOffset, job.out.length()));
            job.readOffset = job.out.length();
            truncated = job.truncated;
        }
        boolean running = job.process.isAlive();
        Integer exitCode = running ? null : job.process.exitValue();
        return new Read(id, chunk, running, exitCode, truncated);
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

    public List<Status> list() {
        var statuses = new ArrayList<Status>();
        for (Job job : jobs.values())
            statuses.add(new Status(job.id, job.command, job.process.isAlive()));
        return statuses;
    }

    public void killAll() {
        for (Job job : jobs.values())
            if (job.process.isAlive()) {
                job.killed = true;
                ProcessRunner.killTree(job.process);
            }
    }

    public record Read(String id, String output, boolean running, Integer exitCode, boolean truncated) {
    }

    public record Status(String id, String command, boolean running) {
    }

    /** A job that finished on its own (not killed): its id, the command, and the process exit code. */
    public record Completion(String id, String command, int exitCode) {
    }
}
