package org.pijota.extensions.jobs;

import org.pijota.tools.Tool;
import org.pijota.util.ProcessRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Registry of fire-and-forget shell jobs the agent starts via {@code jobs_run} and later reads or kills (via
 * {@code jobs_output} / {@code jobs_kill}). Each job's merged stdout+stderr is drained by a daemon thread into a capped
 * rolling buffer; {@link #read} returns only the slice produced since the previous read, so the model sees incremental
 * progress. Unlike the tmux integration this needs no external binary, plain JDK process management, always available
 * and native-image safe.
 */
public final class JobRegistry {
    /** How many finished jobs stay listable. Enough to look back over a session, bounded so it cannot grow forever. */
    private static final int MAX_FINISHED = 20;

    private static final int MAX_BUFFER = 200_000; // cap retained output per job (characters, not bytes) to bound
                                                   // memory

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final List<Consumer<Completion>> completionListeners = new CopyOnWriteArrayList<>();

    public JobRegistry() {
        // Cleanup is driven by JobsExtension.onShutdown (the extension lifecycle), not a per-instance JVM shutdown
        // hook. That keeps shutdown ordered/logged by ExtensionRegistry and avoids one dangling hook per instance.
    }

    /** {@code onComplete} fires (off the drain thread) when a job exits on its own, not when it's killed. */
    public JobRegistry(Consumer<Completion> onComplete) {
        this();
        onCompletion(onComplete);
    }

    /** Adds a completion listener. One registry serves both the TUI and the jobs extension. */
    public void onCompletion(Consumer<Completion> listener) {
        if (listener != null)
            completionListeners.add(listener);
    }

    private void fireCompletion(Completion completion) {
        for (var listener : completionListeners) {
            try {
                listener.accept(completion);
            } catch (Exception ignored) {}
        }
    }

    private static final class Job {
        final String id;
        final String command;
        final String name;
        final Process process;
        final long startedAtMs = System.currentTimeMillis();
        final StringBuilder out = new StringBuilder(); // guarded by this
        int readOffset; // guarded by this
        boolean truncated; // guarded by this
        volatile boolean killed; // set before an intentional kill so its exit isn't reported as completion
        // A process the user has not backgrounded: a tool call is still waiting on it, and that call is what reports
        // it. Completion notices and jobs_output are for processes nobody is waiting on.
        volatile boolean inTurn;
        // Whether every byte the process wrote has reached `out`. Process exit does NOT imply this: the pipe can
        // still hold output the drain thread hasn't copied yet. Adopted jobs have no drain thread, so they start
        // true and the tool's own pump keeps feeding.
        volatile boolean drained = true;

        Job(String id, String command, String name, Process process) {
            this.id = id;
            this.command = command;
            this.name = name;
            this.process = process;
        }

        void append(char[] buf, int len) {
            synchronized (this) {
                int room = MAX_BUFFER - out.length();
                if (room > 0)
                    out.append(buf, 0, Math.min(len, room));
                if (len > room)
                    truncated = true;
            }
        }
    }

    /**
     * Registers a process that is running under a tool call, so it appears in the one running-process list and can be
     * stopped by id like anything else. It keeps this id if the user later backgrounds it: backgrounding becomes a
     * change of state rather than a rebirth, and the id on screen while it ran is the id you type afterwards.
     *
     * <p>
     * No drain thread and no completion notice: the tool owns the output and its result is the report.
     */
    public String register(Process process, String command, String name) {
        String id = "bg_" + seq.incrementAndGet();
        var job = new Job(id, command, (name != null && !name.isBlank()) ? name : command, process);
        job.inTurn = true;
        jobs.put(id, job);
        process.onExit().thenAccept(p -> {
            if (!job.killed && !job.inTurn) // backgrounded before it finished: nobody is waiting, so say so
                fireCompletion(new Completion(job.id, job.command, job.name, p.exitValue(),
                                                System.currentTimeMillis() - job.startedAtMs));
        });
        return id;
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
            env.forEach((k, v) -> {
                if (k != null && v != null) // ProcessBuilder's environment map NPEs on a null key/value
                    pb.environment().put(k, v);
            });
        Process process = pb.start();
        // A background job gets no input: close the child's stdin so a command that reads it (cat, an interactive
        // tool) can't block forever, which would also leave the drain thread stuck and the job "running" for good.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {}
        String id = "bg_" + seq.incrementAndGet();
        String label = (name != null && !name.isBlank()) ? name : String.join(" ", command);
        var job = new Job(id, String.join(" ", command), label, process);
        jobs.put(id, job);
        job.drained = false; // a drain thread owns the pipe now; nothing is fully captured until it hits EOF
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
                job.append(buf, n);
            }
        } catch (IOException ignored) {
            // stream closed because the process ended or was killed, nothing more to drain
        } finally {
            job.drained = true; // EOF (or a dead stream): everything the process wrote is now in the buffer
        }
        // EOF means the process has exited (or was killed) and all output is buffered: report completion so the agent
        // is notified instead of having to poll. waitFor() yields the exit code; intentional kills are not reported.
        try {
            int code = job.process.waitFor();
            if (!job.killed)
                fireCompletion(new Completion(job.id, job.command, job.name, code,
                                                System.currentTimeMillis() - job.startedAtMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Adopts a process handed off by a detached tool call (the user backgrounded a running command). The output feed is
     * re-pointed at this job's buffer, atomically, so the seed and the live feed never lose or duplicate a chunk, and
     * the buffer's read offset starts AFTER the seed: the detach's synthetic tool result already showed the output so
     * far, so {@code jobs_output} reports only what is new. No drain thread is needed; the tool's own pump keeps
     * feeding, and {@code onExit} delivers the completion.
     */
    public Adopted adopt(Tool.DetachedProcess detached, String name) {
        // Already registered (it was running in the turn): keep its id and just change state. A new id here would
        // rename the thing at the exact moment the user looked away from it.
        var existing = jobs.values().stream().filter(j -> j.process == detached.process()).findFirst().orElse(null);
        if (existing != null) {
            String seeded;
            synchronized (existing) {
                seeded = detached.redirectOutput().apply(chunk -> {
                    char[] chars = chunk.toCharArray();
                    existing.append(chars, chars.length);
                });
                String seed = seeded.length() > MAX_BUFFER ? seeded.substring(seeded.length() - MAX_BUFFER) : seeded;
                if (seeded.length() > MAX_BUFFER)
                    existing.truncated = true;
                existing.out.append(seed);
                existing.readOffset = existing.out.length();
            }
            existing.inTurn = false; // its exit is now worth a notice: no tool call is waiting for it
            return new Adopted(existing.id, seeded);
        }
        String id = "bg_" + seq.incrementAndGet();
        String label = (name != null && !name.isBlank()) ? name : detached.command();
        var job = new Job(id, detached.command(), label, detached.process());
        // Seed under the job lock: the switch happens while we hold it, so a concurrently delivered live chunk blocks
        // on job.append until the seed is written. The buffer can never interleave live output before the seed.
        String outputSoFar;
        synchronized (job) {
            outputSoFar = detached.redirectOutput().apply(chunk -> {
                char[] chars = chunk.toCharArray();
                job.append(chars, chars.length);
            });
            String seed = outputSoFar;
            if (seed.length() > MAX_BUFFER) {
                job.truncated = true;
                seed = seed.substring(seed.length() - MAX_BUFFER);
            }
            job.out.append(seed);
            job.readOffset = job.out.length();
        }
        jobs.put(id, job);
        detached.process().onExit().thenAccept(p -> {
            if (!job.killed)
                fireCompletion(new Completion(job.id, job.command, job.name, p.exitValue(),
                                                System.currentTimeMillis() - job.startedAtMs));
        });
        return new Adopted(id, outputSoFar);
    }

    /** Result of {@link #adopt}: the new job id plus the output the command produced before the hand-off. */
    public record Adopted(String id, String outputSoFar) {
    }

    /** The synthetic result that resolves a backgrounded tool call, what the model sees instead of the output. */
    public static Tool.ToolResult backgroundedResult(String jobId, String outputSoFar) {
        // No invitation to poll. This used to say "poll new output with jobs_output", and the model obeyed: it
        // looped on jobs_output, printing a card per read, each one a mid-sentence fragment. Backgrounding exists so
        // nobody has to watch the thing -- and a completion notice already arrives on its own, so the honest
        // instruction is to carry on.
        var content = new StringBuilder("Moved to the background by the user as job \"").append(jobId)
                                        .append("\". It is still running, and its output will be delivered to you")
                                        .append(" automatically when it finishes. Do not call jobs_output(id=\"")
                                        .append(jobId).append("\") now -- it blocks, which is the very wait the user")
                                        .append(" just chose to end. Do not start it again. Carry on with the rest of")
                                        .append(" the work, or reply to the user. jobs_kill stops it.");
        String tail = lastLines(outputSoFar, 10);
        if (!tail.isBlank())
            content.append("\nOutput so far:\n").append(tail);
        return Tool.ToolResult.success(content.toString(), Map.of("jobId", jobId, "backgrounded", true));
    }

    private static String lastLines(String text, int keep) {
        if (text == null || text.isBlank())
            return "";
        var lines = text.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - keep), lines.size()));
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
        return new Read(id, chunk, running, job.drained, exitCode, truncated, outSize, job.inTurn);
    }

    /**
     * Whether this job was stopped deliberately rather than dying on its own. A killed process exits 143, which reads
     * to a model as a crash worth retrying -- and it did retry. The owning tool asks this so its result can report a
     * decision instead of a fault.
     */
    public boolean wasKilled(String id) {
        var job = jobs.get(id);
        return job != null && job.killed;
    }

    /**
     * Forgets the oldest finished jobs beyond {@link #MAX_FINISHED}.
     *
     * <p>
     * The map only ever grew, so a long session's {@code /jobs} filled with exited entries -- and jobs_list is a tool,
     * so the model paid for that wall in tokens on every call. Running jobs are never dropped, and the retained window
     * is far larger than the handful a tool consults right after its process exits.
     */
    private void pruneFinished() {
        var finished = jobs.values().stream().filter(j -> !j.process.isAlive())
                                        .sorted(Comparator.comparingLong(j -> j.startedAtMs)).toList();
        for (int i = 0; i < finished.size() - MAX_FINISHED; i++)
            jobs.remove(finished.get(i).id);
    }

    /** Check whether a job is still running without consuming any buffered output. */
    public boolean isRunning(String id) {
        Job job = jobs.get(id);
        return job != null && job.process.isAlive();
    }

    /**
     * Stops a job by signalling its whole process tree (not just the shell wrapper). {@code force=true} is SIGKILL,
     * forced, and suppresses the completion notification since the exit is intentional; {@code force=false} is SIGTERM,
     * graceful, so a process that traps it and later exits on its own is still reported complete. Idempotent: returns
     * {@code true} for any known job (a no-op if it already finished), {@code false} only if the id is unknown. Uses
     * the Java {@code ProcessHandle} API throughout, so it works the same on Linux, macOS, and Windows.
     */
    public boolean stop(String id, boolean force) {
        Job job = jobs.get(id);
        if (job == null)
            return false;
        if (job.process.isAlive()) {
            if (force) {
                job.killed = true; // a forced kill is intentional, don't report it as a natural completion
                ProcessRunner.killTree(job.process);
            } else {
                job.process.descendants().forEach(ProcessHandle::destroy);
                job.process.destroy();
            }
        }
        return true;
    }

    public List<Status> list() {
        pruneFinished();
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
                                            outSize, truncated, System.currentTimeMillis() - job.startedAtMs,
                                            job.inTurn));
        }
        return statuses;
    }

    public void killAll() {
        for (String id : jobs.keySet())
            stop(id, true);
    }

    public record Read(String id, String output, boolean running, boolean drained, Integer exitCode, boolean truncated,
                                    int outputChars, boolean inTurn) {
    }

    public record Status(String id, String command, String name, boolean running, Integer exitCode, int outputChars,
                                    boolean truncated, long elapsedMs, boolean inTurn) {
    }

    /** A job that finished on its own (not killed): its id, the command, the name, and the process exit code. */
    public record Completion(String id, String command, String name, int exitCode, long elapsedMs) {
    }
}
