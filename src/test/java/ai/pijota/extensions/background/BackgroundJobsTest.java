package ai.pijota.extensions.background;

import ai.pijota.util.Executables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BackgroundJobsTest {
    @TempDir
    Path tempDir;

    private static List<String> sh(String script) {
        return List.of("sh", "-c", script);
    }

    @Test
    void runsToCompletionAndStreamsOutputIncrementally() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var jobs = new BackgroundJobs();

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
        var jobs = new BackgroundJobs();

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
        var completions = new ArrayBlockingQueue<BackgroundJobs.Completion>(4);
        var jobs = new BackgroundJobs(completions::add);

        jobs.start(sh("exit 3"), tempDir);
        var c = completions.poll(5, TimeUnit.SECONDS);
        assertNotNull(c, "completion should fire when the job exits");
        assertEquals(3, c.exitCode());
    }

    @Test
    void doesNotFireCompletionForAnIntentionalKill() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var completions = new ArrayBlockingQueue<BackgroundJobs.Completion>(4);
        var jobs = new BackgroundJobs(completions::add);

        String id = jobs.start(sh("sleep 30"), tempDir);
        Thread.sleep(100);
        jobs.kill(id);
        assertNull(completions.poll(2, TimeUnit.SECONDS), "a killed job must not be reported as a completion");
    }

    @Test
    void unknownJobIdReadsAndKillsCleanly() {
        var jobs = new BackgroundJobs();
        assertNull(jobs.read("bg_999"));
        assertFalse(jobs.kill("bg_999"));
    }

    @Test
    void panelShowsOnlyRunningJobsAndIsEmptyOtherwise() {
        assertEquals(List.of(), BackgroundJobsExtension.renderPanel(List.of()));
        var statuses = List.of(new BackgroundJobs.Status("bg_1", "npm test", true),
                                        new BackgroundJobs.Status("bg_2", "old", false));
        var panel = BackgroundJobsExtension.renderPanel(statuses);
        assertTrue(panel.get(0).contains("1 running"), panel.toString());
        assertTrue(panel.stream().anyMatch(l -> l.contains("bg_1") && l.contains("npm test")));
        assertFalse(panel.stream().anyMatch(l -> l.contains("bg_2")), "finished jobs are not shown");
    }
}
