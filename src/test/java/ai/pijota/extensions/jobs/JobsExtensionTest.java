package ai.pijota.extensions.jobs;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.tools.Tool;
import ai.pijota.util.Executables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JobsExtensionTest {
    @TempDir
    Path tempDir;

    private FakeExtensionAPI initWithFlag(boolean on) {
        var runtime = new FakeExtensionRuntime();
        if (on)
            runtime.setFlag("jobs", "true");
        var api = new FakeExtensionAPI(runtime);
        new JobsExtension().init(api);
        return api;
    }

    private Tool.ToolResult run(FakeExtensionAPI api, String tool, Map<String, Object> args) throws Exception {
        return api.tool(tool).execute().execute("id", new Tool.Args(args), tempDir.toString()).get();
    }

    @Test
    void noToolsWithoutTheFlag() {
        var api = initWithFlag(false);
        assertTrue(api.tools().isEmpty(), "background tools must be opt-in (--background)");
    }

    @Test
    void registersTheJobToolsAndPanelWithTheFlag() {
        var api = initWithFlag(true);
        assertNotNull(api.tool("jobs_run"));
        assertNotNull(api.tool("jobs_output"));
        assertNotNull(api.tool("jobs_list"));
        assertNotNull(api.tool("jobs_kill"));
        assertFalse(api.statusLineSuppliers().isEmpty());
    }

    @Test
    void jobsListReportsEmptyThenReflectsAStartedJob() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        assertTrue(run(api, "jobs_list", Map.of()).content().contains("no background jobs"));

        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");
        var listed = run(api, "jobs_list", Map.of()).content();
        assertTrue(listed.contains(id) && listed.contains("running") && listed.contains("sleep 30"), listed);
        run(api, "jobs_kill", Map.of("id", id));
    }

    @Test
    void toolsValidateTheirArguments() throws Exception {
        var api = initWithFlag(true);
        assertTrue(run(api, "jobs_run", Map.of()).isError(), "missing command");
        assertTrue(run(api, "jobs_output", Map.of("id", "bg_999")).content().contains("No background job"));
        assertTrue(run(api, "jobs_kill", Map.of("id", "bg_999")).isError());
    }

    @Test
    void runsACommandInTheBackgroundAndReadsItsOutput() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);

        var started = run(api, "jobs_run", Map.of("command", "echo hi"));
        assertFalse(started.isError(), started.content());
        String id = (String) started.details().get("jobId");
        assertNotNull(id);

        var out = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000;
        while (!out.toString().contains("hi") && System.currentTimeMillis() < deadline) {
            out.append(run(api, "jobs_output", Map.of("id", id)).content());
            Thread.sleep(20);
        }
        assertTrue(out.toString().contains("hi"), out.toString());
    }

    @Test
    void jobsOutputFilterReturnsOnlyMatchingLines() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "printf 'apple\\nbanana\\ncherry\\n'")).details()
                                        .get("jobId");

        // wait up to 5s for output, return only the "banana" line
        var out = run(api, "jobs_output", Map.of("id", id, "filter", "banana", "timeout", 5)).content();
        assertTrue(out.contains("banana"), out);
        assertFalse(out.contains("apple"), out);
        assertFalse(out.contains("cherry"), out);
    }

    @Test
    void jobsOutputWaitsForLateOutput() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 1; echo ready")).details().get("jobId");

        long start = System.currentTimeMillis();
        var out = run(api, "jobs_output", Map.of("id", id, "timeout", 5)).content();
        assertTrue(out.contains("ready"), out);
        assertTrue(System.currentTimeMillis() - start >= 500,
                                        "should have blocked until output arrived, not returned now");
    }

    // ---- jobs_run edge cases ----

    @Test
    void jobsRunRejectsBlankCommand() throws Exception {
        var api = initWithFlag(true);
        assertTrue(run(api, "jobs_run", Map.of("command", "   ")).isError());
    }

    @Test
    void jobsRunRejectsMissingWorkdir() throws Exception {
        var api = initWithFlag(true);
        var r = run(api, "jobs_run", Map.of("command", "echo hi", "workdir", tempDir.resolve("nope").toString()));
        assertTrue(r.isError());
        assertTrue(r.content().contains("does not exist"), r.content());
    }

    @Test
    void jobsRunRunsInProvidedWorkdir() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        var sub = Files.createDirectory(tempDir.resolve("sub"));
        run(api, "jobs_run", Map.of("command", "echo m > out.txt", "workdir", sub.toString()));
        long deadline = System.currentTimeMillis() + 5000;
        while (!Files.exists(sub.resolve("out.txt")) && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        assertTrue(Files.exists(sub.resolve("out.txt")), "ran in the provided workdir");
    }

    @Test
    void jobsRunReturnsIdInContentAndDetails() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        var r = run(api, "jobs_run", Map.of("command", "echo hi"));
        String id = (String) r.details().get("jobId");
        assertNotNull(id);
        assertTrue(r.content().contains(id), r.content());
    }

    // ---- jobs_output edge cases ----

    @Test
    void jobsOutputRejectsInvalidFilterRegex() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 2")).details().get("jobId");
        var r = run(api, "jobs_output", Map.of("id", id, "filter", "[unclosed"));
        assertTrue(r.isError());
        assertTrue(r.content().contains("Invalid filter regex"), r.content());
        run(api, "jobs_kill", Map.of("id", id));
    }

    @Test
    void jobsOutputReportsNoMatchYetForARunningJob() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 2")).details().get("jobId");
        var r = run(api, "jobs_output", Map.of("id", id, "filter", "ZZZNOPE")); // timeout 0 → returns now
        assertFalse(r.isError());
        assertTrue(r.content().contains("no lines matching filter"), r.content());
        assertTrue(r.content().contains("still running"), r.content());
        run(api, "jobs_kill", Map.of("id", id));
    }

    @Test
    void jobsOutputWithoutTimeoutReturnsPromptly() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 5")).details().get("jobId");
        long t0 = System.currentTimeMillis();
        var r = run(api, "jobs_output", Map.of("id", id)); // no timeout → 0, must not block
        assertTrue(System.currentTimeMillis() - t0 < 1000, "should return promptly, not wait");
        assertTrue(r.content().contains("still running"), r.content());
        run(api, "jobs_kill", Map.of("id", id));
    }

    @Test
    void jobsOutputShowsExitStatusOnceFinished() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "echo done")).details().get("jobId");
        String content = "";
        long deadline = System.currentTimeMillis() + 5000;
        while (!content.contains("exited") && System.currentTimeMillis() < deadline)
            content = run(api, "jobs_output", Map.of("id", id, "timeout", 2)).content();
        assertTrue(content.contains("exited with code 0"), content);
    }

    // ---- jobs_kill ----

    @Test
    void jobsKillKillsAValidJob() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");
        var r = run(api, "jobs_kill", Map.of("id", id));
        assertFalse(r.isError());
        assertTrue(r.content().contains("Killed background job " + id), r.content());
    }

    // ---- monitor (completion notifications) ----

    @Test
    void monitorNotifiesWhenAJobSucceeds() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        run(api, "jobs_run", Map.of("command", "echo hi"));
        assertTrue(awaitUserMessage(api, "finished · exit 0", 5000), api.sentUserMessages().toString());
        assertTrue(api.sentUserMessages().stream().anyMatch(m -> m.endsWith("|NEXT_TURN")),
                                        api.sentUserMessages().toString());
    }

    @Test
    void monitorSteersWhenAJobFails() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        run(api, "jobs_run", Map.of("command", "exit 2"));
        assertTrue(awaitUserMessage(api, "FAILED · exit 2", 5000), api.sentUserMessages().toString());
        assertTrue(api.sentUserMessages().stream().anyMatch(m -> m.endsWith("|STEER")),
                                        api.sentUserMessages().toString());
    }

    @Test
    void monitorStaysSilentWhenAJobIsKilled() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");
        run(api, "jobs_kill", Map.of("id", id));
        Thread.sleep(600);
        assertFalse(api.sentUserMessages().stream().anyMatch(m -> m.contains("finished") || m.contains("FAILED")),
                                        api.sentUserMessages().toString());
    }

    private boolean awaitUserMessage(FakeExtensionAPI api, String needle, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (api.sentUserMessages().stream().anyMatch(m -> m.contains(needle)))
                return true;
            Thread.sleep(20);
        }
        return false;
    }
}
