package org.pijota.extensions.jobs;

import org.pijota.tools.Tool;
import org.pijota.util.Executables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

        assertTrue(jobs.stop(id, true));
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
        jobs.stop(id, true);
        assertNull(completions.poll(2, TimeUnit.SECONDS), "a killed job must not be reported as a completion");
    }

    @Test
    void unknownJobIdReadsAndKillsCleanly() {
        var jobs = new JobRegistry();
        assertNull(jobs.read("bg_999"));
        assertFalse(jobs.stop("bg_999", true));
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
        jobs.stop(id, true);
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
        assertNull(completions.poll(1, TimeUnit.SECONDS), "killAll is intentional, no completion notifications");
    }

    @Test
    void killingAnAlreadyFinishedJobReturnsTrue() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        String id = jobs.start(sh("echo hi"), tempDir);
        drainUntilDone(jobs, id);
        assertTrue(jobs.stop(id, true), "the job still exists in the registry even after it finished");
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
        var statuses = List.of(new JobRegistry.Status("bg_1", "npm test", "npm test", true, null, 0, false, 0),
                                        new JobRegistry.Status("bg_2", "old", "old", false, 0, 0, false, 0));
        var panel = JobsExtension.renderPanel(statuses);
        assertTrue(panel.get(0).contains("1 running"), panel.toString());
        assertTrue(panel.stream().anyMatch(l -> l.contains("bg_1") && l.contains("npm test")));
        assertFalse(panel.stream().anyMatch(l -> l.contains("bg_2")), "finished jobs are not shown");
    }

    @Test
    void panelCapsRowsAndShortensLongCommands() {
        var many = new ArrayList<JobRegistry.Status>();
        for (int i = 1; i <= 8; i++)
            many.add(new JobRegistry.Status("bg_" + i, "cmd" + i, "cmd" + i, true, null, 0, false, 0));
        var panel = JobsExtension.renderPanel(many);
        assertTrue(panel.get(0).contains("8 running"), panel.toString());
        assertTrue(panel.stream().anyMatch(l -> l.contains("+2 more")), panel.toString()); // 8 jobs − 6 row cap

        var longCmd = List.of(new JobRegistry.Status("bg_1", "x".repeat(120), "x".repeat(120), true, null, 0, false,
                                        0));
        assertTrue(JobsExtension.renderPanel(longCmd).stream().anyMatch(l -> l.contains("…")),
                                        "a long command is elided");
    }

    /**
     * Two spaces read as column alignment, and none of these rows line up. The completion notice already used " . ", so
     * the same screen showed one job's facts joined two different ways; this keeps the whole extension on one.
     */
    @Test
    void jobRowsSeparateFactsTheSameWayEverywhereElseDoes() {
        var statuses = List.of(new JobRegistry.Status("bg_1", "npm run dev", "web-server", true, null, 12, false, 4200),
                                        new JobRegistry.Status("bg_2", "mvn test", "mvn test", false, 1, 0, true, 900));

        var offenders = new ArrayList<String>();
        for (String row : JobsExtension.renderList(statuses).split("\n"))
            if (row.strip().contains("  "))
                offenders.add("list: " + row);
        for (String row : JobsExtension.renderPanel(statuses))
            if (row.strip().contains("  "))
                offenders.add("panel: " + row);

        assertTrue(offenders.isEmpty(), "a two-space gap is column alignment, and nothing here aligns: " + offenders);
    }

    /**
     * A running job and a running sub-agent are the same kind of thing, so both rows say how long it has been going.
     */
    @Test
    void thePanelSaysHowLongAJobHasBeenRunning() {
        var statuses = List.of(new JobRegistry.Status("bg_1", "npm test", "npm test", true, null, 0, false, 65_000));
        var row = JobsExtension.renderPanel(statuses).stream().filter(l -> l.contains("bg_1")).findFirst()
                                        .orElseThrow();
        assertTrue(row.matches(".*\\d+[ms].*"), "no elapsed time on a running job: " + row);
    }

    @Test
    void renderListShowsStatusExitAndCommand() {
        assertEquals("(no background jobs)", JobsExtension.renderList(List.of()));
        var rows = JobsExtension.renderList(List.of(
                                        new JobRegistry.Status("bg_1", "npm run dev", "npm run dev", true, null, 0,
                                                                        false, 0),
                                        new JobRegistry.Status("bg_2", "mvn test", "mvn test", false, 1, 0, false, 0)));
        assertTrue(rows.contains("bg_1 \u00b7 running \u00b7 0B \u00b7 npm run dev"), rows);
        assertTrue(rows.contains("bg_2 \u00b7 exit 1 \u00b7 0B \u00b7 mvn test"), rows);
    }

    @Test
    void sigtermTerminatesButStillReportsCompletion() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(2);
        var jobs = new JobRegistry(completions::add);
        String id = jobs.start(sh("sleep 30"), tempDir);
        Thread.sleep(100);

        assertTrue(jobs.stop(id, false));
        // SIGTERM is graceful, killed is NOT set, so the natural exit must still fire a completion
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

        assertTrue(jobs.stop(id, true));
        long deadline = System.currentTimeMillis() + 3000;
        while (jobs.isRunning(id) && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        assertFalse(jobs.isRunning(id), "SIGKILL stops the job");
        assertNull(completions.poll(1, TimeUnit.SECONDS), "a forced kill is intentional, no completion");
    }

    @Test
    void stopRejectsUnknownJobsAndIsIdempotentOnKnownOnes() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        assertFalse(jobs.stop("bg_999", false), "unknown job");
        String id = jobs.start(sh("sleep 30"), tempDir);
        assertTrue(jobs.stop(id, false), "known job · SIGTERM");
        jobs.stop(id, true); // and a forced stop is fine afterward
    }

    // -- adoption of user-backgrounded (detached) commands --

    private static Tool.DetachedProcess handoff(Process process, String command, String seed,
                                    AtomicReference<Consumer<String>> feed) {
        return new Tool.DetachedProcess(process, command, next -> {
            feed.set(next);
            return seed;
        }, result -> {});
    }

    @Test
    void adoptSeedsTheBufferAndReportsOnlyNewOutputToReads() throws Exception {
        assumeTrue(Executables.isAvailable("sleep"), "needs a long-lived POSIX process");
        var jobs = new JobRegistry();
        Process process = new ProcessBuilder("sleep", "30").start();
        var feed = new AtomicReference<Consumer<String>>();
        try {
            var adopted = jobs.adopt(handoff(process, "npm run dev", "early output\n", feed), "npm run dev");

            assertEquals("early output\n", adopted.outputSoFar(), "the seed travels back for the synthetic result");
            var first = jobs.read(adopted.id());
            assertEquals("", first.output(), "the synthetic result already showed the seed, reads start after it");
            assertTrue(first.running());

            feed.get().accept("late output\n"); // the tool's pump keeps feeding the adopted job
            var second = jobs.read(adopted.id());
            assertEquals("late output\n", second.output());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void adoptedJobReportsCompletionWithExitCodeOnItsOwnExit() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new JobRegistry();
        var completions = new LinkedBlockingQueue<JobRegistry.Completion>();
        jobs.onCompletion(completions::add);
        Process process = new ProcessBuilder("sh", "-c", "exit 3").start();
        var feed = new AtomicReference<Consumer<String>>();

        var adopted = jobs.adopt(handoff(process, "flaky", "", feed), null);

        var completion = completions.poll(5, TimeUnit.SECONDS);
        assertNotNull(completion, "onExit must fire the completion listeners");
        assertEquals(adopted.id(), completion.id());
        assertEquals(3, completion.exitCode());
    }

    @Test
    void adoptedJobCanBeKilledLikeAnyOther() throws Exception {
        assumeTrue(Executables.isAvailable("sleep"), "needs a long-lived POSIX process");
        var jobs = new JobRegistry();
        Process process = new ProcessBuilder("sleep", "30").start();
        var feed = new AtomicReference<Consumer<String>>();
        var adopted = jobs.adopt(handoff(process, "sleep 30", "", feed), null);

        assertTrue(jobs.isRunning(adopted.id()));
        assertTrue(jobs.stop(adopted.id(), true));
        process.onExit().get(5, TimeUnit.SECONDS);
        assertFalse(jobs.isRunning(adopted.id()));
    }

    @Test
    void backgroundedResultCarriesJobIdGuidanceAndTail() {
        var result = JobRegistry.backgroundedResult("bg_7", "line1\nline2\n");
        assertFalse(result.isError());
        assertEquals("bg_7", result.details().get("jobId"));
        assertEquals(true, result.details().get("backgrounded"));
        assertTrue(result.content().contains("jobs_output(id=\"bg_7\")"));
        assertTrue(result.content().contains("line2"));
        assertTrue(result.content().contains("Do not start it again"), result.content());
    }

    /**
     * The old text said "poll new output with jobs_output", and the model did exactly that -- a read loop, one card per
     * read, each a mid-sentence fragment. Backgrounding exists so nobody has to watch the thing, and a completion
     * notice already arrives on its own.
     */
    @Test
    void backgroundedResultDoesNotAskTheModelToPoll() {
        String content = JobRegistry.backgroundedResult("bg_7", "line1\n").content().toLowerCase();
        assertFalse(content.contains("poll new output"), content);
        assertTrue(content.contains("do not call jobs_output"), content);
        assertTrue(content.contains("delivered to you automatically"), content);
    }

    @Test
    void backgroundedResultOmitsTheOutputSectionWhenThereIsNoTail() {
        var result = JobRegistry.backgroundedResult("bg_9", "");
        assertEquals("bg_9", result.details().get("jobId"));
        assertFalse(result.content().contains("Output so far"), result.content());
        assertTrue(result.content().contains("jobs_output(id=\"bg_9\")"), result.content());
    }

    @Test
    void aStdinReadingJobDoesNotHangBecauseStdinIsClosed() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<JobRegistry.Completion>(2);
        var jobs = new JobRegistry(completions::add);
        // `cat` with no file reads stdin forever; a backgrounded job gets no input, so start() closes the child's
        // stdin: cat hits EOF immediately and exits, rather than hanging (and stranding its drain thread).
        jobs.start(sh("cat"), tempDir);
        assertNotNull(completions.poll(5, TimeUnit.SECONDS),
                                        "a stdin-reading background job must not hang: stdin is closed at launch");
    }

    @Test
    void isRunningIsFalseForAnUnknownJob() {
        assertFalse(new JobRegistry().isRunning("bg_nope"));
    }

    @Test
    void adoptTrimsAnOversizedSeedAndFlagsTruncation() throws Exception {
        assumeTrue(Executables.isAvailable("sleep"), "needs a long-lived POSIX process");
        var jobs = new JobRegistry();
        Process process = new ProcessBuilder("sleep", "30").start();
        var feed = new AtomicReference<Consumer<String>>();
        try {
            String big = "x".repeat(250_000); // over the 200KB retained-buffer cap
            var adopted = jobs.adopt(handoff(process, "big", big, feed), null);
            assertEquals(250_000, adopted.outputSoFar().length(), "the full pre-switch tail travels back untrimmed");
            var read = jobs.read(adopted.id());
            assertEquals("", read.output(), "reads begin after the seed");
            assertTrue(read.truncated(), "the retained buffer dropped the oldest and flagged truncation");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void renderListShowsADistinctNameLabelAlongsideTheCommand() {
        var rows = JobsExtension.renderList(List.of(new JobRegistry.Status("bg_1", "npm run dev", "web-server", true,
                                        null, 0, false, 0)));
        assertTrue(rows.contains("web-server"), rows);
        assertTrue(rows.contains("npm run dev"), rows);
    }
}
