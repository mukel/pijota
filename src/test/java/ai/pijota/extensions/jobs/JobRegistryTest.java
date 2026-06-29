package ai.pijota.extensions.jobs;

import ai.pijota.util.Executables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JobRegistryTest {
    @TempDir
    Path tempDir;

    private static List<String> sh(String script) {
        return List.of("sh", "-c", script);
    }

    @Test
    void runsToCompletionAndStreamsOutputIncrementally() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();

        String id = jobs.start(sh("echo hello"), tempDir);
        assertTrue(id.startsWith("bg_"));

        // accumulate incremental reads until the expected output shows up (robust to the exit/flush race)
        var out = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000;
        while (!out.toString().contains("hello") && System.currentTimeMillis() < deadline) {
            out.append(jobs.read(id).output());
            Thread.sleep(20);
        }
        assertTrue(out.toString().contains("hello"), out.toString());

        // job is finished now: status reflects the exit, and already-consumed output isn't repeated
        var finished = jobs.read(id);
        assertFalse(finished.running(), "job should have finished");
        assertEquals(0, finished.exitCode());
        assertEquals("", finished.output(), "reads are incremental");
    }

    @Test
    void killStopsALongRunningJob() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();

        String id = jobs.start(sh("sleep 30"), tempDir);
        assertTrue(jobs.read(id).running(), "should be running");
        assertTrue(jobs.list().stream().anyMatch(s -> s.id().equals(id) && s.running()));

        assertTrue(jobs.kill(id));
        long deadline = System.currentTimeMillis() + 3000;
        while (jobs.read(id).running() && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        assertFalse(jobs.read(id).running(), "killed job should no longer be running");
    }

    @Test
    void firesCompletionWithExitCodeWhenAJobEndsOnItsOwn() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(4);
        var jobs = new JobRegistry(completions::add);

        jobs.start(sh("exit 3"), tempDir);
        var c = completions.poll(5, TimeUnit.SECONDS);
        assertNotNull(c, "completion should fire when the job exits");
        assertEquals(3, c.exitCode());
    }

    @Test
    void doesNotFireCompletionForAnIntentionalKill() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(4);
        var jobs = new JobRegistry(completions::add);

        String id = jobs.start(sh("sleep 30"), tempDir);
        Thread.sleep(100);
        jobs.kill(id);
        assertNull(completions.poll(2, TimeUnit.SECONDS), "a killed job must not be reported as a completion");
    }

    @Test
    void unknownJobIdReadsAndKillsCleanly() {
        var jobs = new JobRegistry();
        assertNull(jobs.read("bg_999"));
        assertFalse(jobs.kill("bg_999"));
    }

    @Test
    void multipleConcurrentJobsAreTrackedIndependently() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        String a = jobs.start(sh("echo aaa"), tempDir);
        String b = jobs.start(sh("echo bbb"), tempDir);
        assertNotEquals(a, b);
        assertTrue(drainUntilDone(jobs, a).output().contains("aaa"));
        assertTrue(drainUntilDone(jobs, b).output().contains("bbb"));
        assertEquals(2, jobs.list().size());
    }

    @Test
    void jobIdsIncrement() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        assertEquals("bg_1", jobs.start(sh("true"), tempDir));
        assertEquals("bg_2", jobs.start(sh("true"), tempDir));
    }

    @Test
    void capturesStderrMergedWithStdout() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        String id = jobs.start(sh("echo out; echo err 1>&2"), tempDir);
        String out = drainUntilDone(jobs, id).output();
        assertTrue(out.contains("out"), out);
        assertTrue(out.contains("err"), out); // redirectErrorStream folds stderr into the same buffer
    }

    @Test
    void runsInTheGivenWorkingDirectory() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        String id = jobs.start(sh("echo marker > out.txt"), tempDir);
        drainUntilDone(jobs, id);
        assertTrue(Files.exists(tempDir.resolve("out.txt")), "command ran in the provided workdir");
    }

    @Test
    void exitCodeIsNullWhileRunning() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        String id = jobs.start(sh("sleep 30"), tempDir);
        var r = jobs.read(id);
        assertTrue(r.running());
        assertNull(r.exitCode());
        jobs.kill(id);
    }

    @Test
    void aJobWithNoOutputStillCompletes() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(2);
        var jobs = new JobRegistry(completions::add);
        jobs.start(sh("exit 0"), tempDir);
        var c = completions.poll(5, TimeUnit.SECONDS);
        assertNotNull(c);
        assertEquals(0, c.exitCode());
    }

    @Test
    void completionCarriesIdCommandAndExitCode() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(2);
        var jobs = new JobRegistry(completions::add);
        String id = jobs.start(sh("echo done"), tempDir);
        var c = completions.poll(5, TimeUnit.SECONDS);
        assertNotNull(c);
        assertEquals(id, c.id());
        assertTrue(c.command().contains("echo done"), c.command());
        assertEquals(0, c.exitCode());
    }

    @Test
    void truncatesOutputBeyondTheBufferCap() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        // ~550KB, well over the 200KB cap
        String id = jobs.start(sh("yes 0123456789 | head -n 50000"), tempDir);
        assertTrue(drainUntilDone(jobs, id).truncated(), "output beyond the cap should be flagged truncated");
    }

    @Test
    void killAllStopsEveryRunningJobAndReportsNoCompletion() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(4);
        var jobs = new JobRegistry(completions::add);
        jobs.start(sh("sleep 30"), tempDir);
        jobs.start(sh("sleep 30"), tempDir);
        jobs.killAll();
        long deadline = System.currentTimeMillis() + 3000;
        while (jobs.list().stream().anyMatch(JobRegistry.Status::running) && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        assertFalse(jobs.list().stream().anyMatch(JobRegistry.Status::running), "all jobs stopped");
        assertNull(completions.poll(1, TimeUnit.SECONDS), "killAll is intentional — no completion notifications");
    }

    @Test
    void killingAnAlreadyFinishedJobReturnsTrue() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        String id = jobs.start(sh("echo hi"), tempDir);
        drainUntilDone(jobs, id);
        assertTrue(jobs.kill(id), "the job still exists in the registry even after it finished");
    }

    /** Accumulate a job's incremental output until it finishes; returns combined output + whether it was truncated. */
    private static Drained drainUntilDone(JobRegistry jobs, String id) throws InterruptedException {
        var out = new StringBuilder();
        boolean truncated = false;
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            var r = jobs.read(id);
            out.append(r.output());
            truncated |= r.truncated();
            if (!r.running())
                break;
            Thread.sleep(15);
        }
        return new Drained(out.toString(), truncated);
    }

    private record Drained(String output, boolean truncated) {
    }

    @Test
    void panelShowsOnlyRunningJobsAndIsEmptyOtherwise() {
        assertEquals(List.of(), JobsExtension.renderPanel(List.of()));
        var statuses = List.of(new JobRegistry.Status("bg_1", "npm test", "npm test", true, null, 0, false),
                                        new JobRegistry.Status("bg_2", "old", "old", false, 0, 0, false));
        var panel = JobsExtension.renderPanel(statuses);
        assertTrue(panel.get(0).contains("1 running"), panel.toString());
        assertTrue(panel.stream().anyMatch(l -> l.contains("bg_1") && l.contains("npm test")));
        assertFalse(panel.stream().anyMatch(l -> l.contains("bg_2")), "finished jobs are not shown");
    }

    @Test
    void panelCapsRowsAndShortensLongCommands() {
        var many = new ArrayList<JobRegistry.Status>();
        for (int i = 1; i <= 8; i++)
            many.add(new JobRegistry.Status("bg_" + i, "cmd" + i, "cmd" + i, true, null, 0, false));
        var panel = JobsExtension.renderPanel(many);
        assertTrue(panel.get(0).contains("8 running"), panel.toString());
        assertTrue(panel.stream().anyMatch(l -> l.contains("+2 more")), panel.toString()); // 8 jobs − 6 row cap

        var longCmd = List.of(new JobRegistry.Status("bg_1", "x".repeat(120), "x".repeat(120), true, null, 0, false));
        assertTrue(JobsExtension.renderPanel(longCmd).stream().anyMatch(l -> l.contains("…")),
                                        "a long command is elided");
    }

    @Test
    void renderListShowsStatusExitAndCommand() {
        assertEquals("(no background jobs)", JobsExtension.renderList(List.of()));
        var rows = JobsExtension.renderList(List.of(
                                        new JobRegistry.Status("bg_1", "npm run dev", "npm run dev", true, null, 0,
                                                                        false),
                                        new JobRegistry.Status("bg_2", "mvn test", "mvn test", false, 1, 0, false)));
        assertTrue(rows.contains("bg_1  running  0B  npm run dev"), rows);
        assertTrue(rows.contains("bg_2  exit 1  0B  mvn test"), rows);
    }

    @Test
    void sigtermTerminatesButStillReportsCompletion() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(2);
        var jobs = new JobRegistry(completions::add);
        String id = jobs.start(sh("sleep 30"), tempDir);
        Thread.sleep(100);

        assertTrue(jobs.signal(id, "SIGTERM"));
        // SIGTERM is graceful — killed is NOT set, so the natural exit must still fire a completion
        var c = completions.poll(5, TimeUnit.SECONDS);
        assertNotNull(c, "a SIGTERM'd job that exits must still report completion");
        assertEquals(id, c.id());
    }

    @Test
    void sigkillStopsAndSuppressesCompletion() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(2);
        var jobs = new JobRegistry(completions::add);
        String id = jobs.start(sh("sleep 30"), tempDir);
        Thread.sleep(100);

        assertTrue(jobs.signal(id, "SIGKILL"));
        long deadline = System.currentTimeMillis() + 3000;
        while (jobs.isRunning(id) && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        assertFalse(jobs.isRunning(id), "SIGKILL stops the job");
        assertNull(completions.poll(1, TimeUnit.SECONDS), "a forced kill is intentional — no completion");
    }

    @Test
    void signalRejectsSigintAndUnknownOrDeadJobs() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        assertFalse(jobs.signal("bg_999", "SIGTERM"), "unknown job");
        String id = jobs.start(sh("sleep 30"), tempDir);
        assertFalse(jobs.signal(id, "SIGINT"), "SIGINT is no longer supported");
        jobs.kill(id);
    }
}
