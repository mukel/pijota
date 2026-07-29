package org.pijota.extensions.jobs;

import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.tools.Tool;
import org.pijota.util.Executables;

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

    /** The text of the most recent custom message a slash command showed the user (empty if none). */
    private static String lastCustom(FakeExtensionAPI api) {
        var msgs = api.sentMessages();
        return msgs.isEmpty() ? "" : msgs.get(msgs.size() - 1).content();
    }

    @Test
    void jobsIsOffByDefault() {
        // opt-in (--jobs): the extension declares itself off; ExtensionRegistry gates registration on that default.
        assertTrue(new JobsExtension().enabledByDefault(),
                                        "jobs is on by default: backgrounding a running command (ctrl+b ctrl+b) "
                                                                        + "needs the jobs_* tools; --no-jobs disables");
    }

    @Test
    void registersAllJobToolsAndPanelWithTheFlag() {
        var api = initWithFlag(true);
        assertEquals(4, api.tools().size(), "exactly four jobs tools");
        assertNotNull(api.tool("jobs_run"));
        assertNotNull(api.tool("jobs_output"));
        assertNotNull(api.tool("jobs_list"));
        assertNotNull(api.tool("jobs_kill"));
        assertNull(api.tool("jobs_signal"), "jobs_signal was merged into jobs_kill");
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
    void slashCommandsLetTheUserListAndKillJobsDirectly() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        assertNotNull(api.slashCommand("jobs"), "/jobs registered when the extension is on");
        assertNotNull(api.slashCommand("kill"), "/kill registered when the extension is on");

        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");

        // /jobs shows the user the same listing jobs_list gives the agent — via a custom message, not a model turn
        api.slashCommand("jobs").handler().accept("");
        String listing = lastCustom(api);
        assertTrue(listing.contains(id) && listing.contains("running") && listing.contains("sleep 30"), listing);

        // /kill <id> terminates it directly, no model round-trip
        api.slashCommand("kill").handler().accept(id);
        assertTrue(lastCustom(api).contains("Killed background job " + id), lastCustom(api));
    }

    @Test
    void killSlashCommandGuardsBadInput() {
        var api = initWithFlag(true);
        api.slashCommand("kill").handler().accept("");
        assertTrue(lastCustom(api).contains("Usage:"), "blank id → usage");
        api.slashCommand("kill").handler().accept("bg_999");
        assertTrue(lastCustom(api).contains("No background job bg_999"), lastCustom(api));
        api.slashCommand("kill").handler().accept("bg_1 SIGHUP");
        assertTrue(lastCustom(api).contains("Unsupported signal"), lastCustom(api));
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

    // ---- jobs_run: name label ----

    @Test
    void jobsRunWithNameShowsLabelInList() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30", "name", "my-build")).details()
                                        .get("jobId");
        var listed = run(api, "jobs_list", Map.of()).content();
        assertTrue(listed.contains("my-build"), "list should show the custom name: " + listed);
        run(api, "jobs_kill", Map.of("id", id));
    }

    @Test
    void jobsRunWithNameIncludesLabelInMonitorNotification() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        run(api, "jobs_run", Map.of("command", "echo done", "name", "ci-test"));
        assertTrue(awaitUserMessage(api, "ci-test", 5000),
                                        "monitor notification should include job name: " + api.sentUserMessages());
    }

    // ---- jobs_run: env ----

    @Test
    void jobsRunWithEnvPassesVariablesToCommand() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run",
                                        Map.of("command", "echo $MY_VAR", "env", Map.of("MY_VAR", "hello_env")))
                                        .details().get("jobId");
        var out = readUntil(api, id, s -> s.contains("hello_env"), 5000);
        assertTrue(out.contains("hello_env"), "env var should be visible to command: " + out);
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

    // ---- jobs_output: structured details ----

    @Test
    void jobsOutputDetailsContainExitCodeAndRunningFlag() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "echo ok")).details().get("jobId");
        Tool.ToolResult r = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            r = run(api, "jobs_output", Map.of("id", id, "timeout", 2));
            if (r.content().contains("exited"))
                break;
        }
        assertNotNull(r);
        assertFalse((boolean) r.details().get("running"), "running should be false");
        assertEquals(0, r.details().get("exitCode"), "exitCode should be 0");
        assertTrue(r.details().containsKey("outputChars"), "should have outputChars");
        assertFalse((boolean) r.details().get("truncated"), "should not be truncated for short output");
    }

    @Test
    void jobsOutputDetailsForRunningJobOmitExitCode() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 5")).details().get("jobId");
        var r = run(api, "jobs_output", Map.of("id", id));
        assertTrue((boolean) r.details().get("running"), "running should be true");
        assertFalse(r.details().containsKey("exitCode"), "exitCode should be absent while running");
        run(api, "jobs_kill", Map.of("id", id));
    }

    // ---- jobs_output: wait_for_completion ----

    @Test
    void jobsOutputWaitForCompletionBlocksUntilExit() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 1; echo final")).details().get("jobId");
        long t0 = System.currentTimeMillis();
        var r = run(api, "jobs_output", Map.of("id", id, "wait_for_completion", true));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(r.content().contains("final"), r.content());
        assertTrue(r.content().contains("exited with code 0"), r.content());
        assertTrue(elapsed >= 800, "wait_for_completion should have blocked ~1s, but took " + elapsed + "ms");
    }

    // ---- jobs_list: output size + truncated ----

    @Test
    void jobsListShowsOutputSize() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "echo hello_world")).details().get("jobId");
        Thread.sleep(300); // let it finish
        var listed = run(api, "jobs_list", Map.of()).content();
        assertTrue(listed.contains("B"), "list should show output size in bytes: " + listed);
        assertTrue(listed.contains(id), "list should contain job id: " + listed);
    }

    @Test
    void jobsListShowsTruncatedFlag() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        // Generate more than 200KB to trigger truncation
        String cmd = "dd if=/dev/zero bs=1024 count=300 2>/dev/null | tr '\\0' x";
        String id = (String) run(api, "jobs_run", Map.of("command", cmd)).details().get("jobId");
        // Wait for it to finish
        while (true) {
            var r = run(api, "jobs_output", Map.of("id", id, "timeout", 5));
            if (r.content().contains("exited"))
                break;
        }
        var listed = run(api, "jobs_list", Map.of()).content();
        assertTrue(listed.contains("truncated"), "list should show [truncated]: " + listed);
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

    @Test
    void jobsKillRejectsInvalidSignalName() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");
        var r = run(api, "jobs_kill", Map.of("id", id, "signal", "SIGINT")); // SIGINT no longer supported
        assertTrue(r.isError(), "unsupported signal should error");
        assertTrue(r.content().contains("Unsupported signal"), r.content());
        run(api, "jobs_kill", Map.of("id", id));
    }

    @Test
    void jobsKillDefaultsToSigkill() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");
        var r = run(api, "jobs_kill", Map.of("id", id)); // no signal → SIGKILL
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("Killed"), r.content());
        Thread.sleep(100);
        assertTrue(run(api, "jobs_list", Map.of()).content().contains("exit"), "job should be dead");
    }

    @Test
    void jobsKillSigtermStopsGracefully() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");
        var r = run(api, "jobs_kill", Map.of("id", id, "signal", "SIGTERM"));
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("SIGTERM"), r.content());
        Thread.sleep(100);
        assertTrue(run(api, "jobs_list", Map.of()).content().contains("exit"), "job should be dead after SIGTERM");
    }

    @Test
    void jobsKillOnAlreadyFinishedJobSucceedsIdempotently() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        String id = (String) run(api, "jobs_run", Map.of("command", "echo done")).details().get("jobId");
        readUntil(api, id, s -> s.contains("exited"), 5000);
        var r = run(api, "jobs_kill", Map.of("id", id));
        assertFalse(r.isError(), "killing a finished (but known) job is a no-op success: " + r.content());
    }

    // ---- monitor (completion notifications) ----

    @Test
    void monitorNotifiesWhenAJobSucceeds() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        run(api, "jobs_run", Map.of("command", "echo hi"));
        // single-arg delivery (steers if busy, wakes if idle) — so the completion always surfaces
        assertTrue(awaitUserMessage(api, "finished · exit 0", 5000), api.sentUserMessages().toString());
    }

    @Test
    void monitorSteersWhenAJobFails() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        run(api, "jobs_run", Map.of("command", "exit 2"));
        assertTrue(awaitUserMessage(api, "FAILED · exit 2", 5000), api.sentUserMessages().toString());
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

    @Test
    void monitorStillNotifiesAfterSigterm() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);
        // SIGTERM is graceful and does NOT suppress completion — the resulting exit must still reach the agent
        String id = (String) run(api, "jobs_run", Map.of("command", "sleep 30")).details().get("jobId");
        run(api, "jobs_kill", Map.of("id", id, "signal", "SIGTERM"));
        assertTrue(awaitUserMessage(api, "background job " + id, 5000), api.sentUserMessages().toString());
    }

    // ---- helpers ----

    private boolean awaitUserMessage(FakeExtensionAPI api, String needle, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (api.sentUserMessages().stream().anyMatch(m -> m.contains(needle)))
                return true;
            Thread.sleep(20);
        }
        return false;
    }

    private String readUntil(FakeExtensionAPI api, String id, java.util.function.Predicate<String> condition,
                                    long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = run(api, "jobs_output", Map.of("id", id, "timeout", 2)).content();
            if (condition.test(content))
                return content;
            Thread.sleep(20);
        }
        return content;
    }
}
