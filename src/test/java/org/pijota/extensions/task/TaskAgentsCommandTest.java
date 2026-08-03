package org.pijota.extensions.task;

import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.tools.ToolRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sub-agents are processes spending tokens, so "what is running" and "stop that one" have to be answerable at any time.
 * The status bar answers the first only while they run and then clears itself, which is correct for a status bar and
 * useless the moment you want to look something up: {@code /agents} is the on-demand form.
 */
class TaskAgentsCommandTest {

    @TempDir
    Path logDir;

    private TaskExtension extension;
    private FakeExtensionAPI api;
    private FakeExtensionRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("pijota.log.dir", logDir.toString());
        runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(new ToolRegistry());
        api = new FakeExtensionAPI(runtime);
        extension = new TaskExtension();
        extension.init(api);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("pijota.log.dir");
    }

    private void run(String args) {
        api.slashCommand("agents").handler().accept(args);
    }

    @Test
    void isRegisteredWithTheAliasesAUserWouldReachFor() {
        var cmd = api.slashCommand("agents");
        assertNotNull(cmd, "/agents is not registered");
        assertTrue(cmd.aliases().contains("agent"), "aliases: " + cmd.aliases());
        assertTrue(cmd.aliases().contains("tasks"), "aliases: " + cmd.aliases());
    }

    @Test
    void listsEveryRunningAgentOnItsOwnLine() {
        extension.onActivity("explore", "Read pom.xml");
        extension.onActivity("explore", "tokens: 2400");
        extension.onActivity("review", "Grep TODO");

        run("");

        String out = api.lastSentMessage();
        assertNotNull(out, "nothing was reported");
        assertTrue(out.contains("task:explore"), out);
        assertTrue(out.contains("task:review"), out);
        assertTrue(out.contains("2K tokens"), "live spend must show, got: " + out);
        assertTrue(out.contains("/agents stop"), "the list must say how to stop one: " + out);
    }

    /** The whole point of an on-demand list: the status bar has already pruned these, the answer must survive. */
    @Test
    void listsFinishedAgentsAfterTheStatusBarHasClearedThem() throws Exception {
        Path log = logDir.resolve("sub-explore-done.log");
        java.nio.file.Files.writeString(log, "body\n");
        extension.onActivity("explore", "log: " + log);
        extension.onActivity("explore", "Read pom.xml");
        extension.finished("explore", true, 15000);

        assertFalse(extension.statusLines().isEmpty(), "precondition: still lingering");
        Thread.sleep(5100); // outlive DONE_LINGER_MS, so the status bar drops it
        assertTrue(extension.statusLines().isEmpty(), "precondition: the status bar has pruned it");

        run("");

        String out = api.lastSentMessage();
        assertNotNull(out, "a finished agent vanished entirely");
        assertTrue(out.contains("task:explore"), out);
        assertTrue(out.contains("15K tokens"), out);
    }

    /** Spend that really happened must not be erased by a stop that reports no tokens of its own. */
    @Test
    void aStoppedAgentKeepsTheTokensItAlreadySpent() {
        extension.onActivity("explore", "tokens: 20000");
        extension.finished("explore", false, 0);

        String row = extension.statusLines().stream().filter(l -> l.contains("explore")).findFirst().orElseThrow();
        assertTrue(row.contains("20K tokens"), "accrued spend was erased: " + row);
    }

    /**
     * Two explores over different subtrees is the point of parallel mode, and every downstream key is the agent's name.
     * Without a distinct label per run they share a status row, a log and a stop.
     */
    @Test
    void concurrentAgentsOfTheSameKindGetDistinctLabels() {
        var tool = new TaskTool(List.of(), null, List::of, () -> "", (a, b) -> {}, (a, b, c) -> {}, Path.of("."), 0);

        assertEquals("explore", tool.allocateLabel("explore"));
        assertEquals("explore#2", tool.allocateLabel("explore"));
        assertEquals("explore#3", tool.allocateLabel("explore"));
        assertEquals("review", tool.allocateLabel("review"), "a different agent is unaffected");
    }

    /** Labels are released when the agent finishes, so a long session does not count up forever. */
    @Test
    void aLabelIsReusedOnceItsAgentHasFinished() {
        var tool = new TaskTool(List.of(), null, List::of, () -> "", (a, b) -> {}, (a, b, c) -> {}, Path.of("."), 0);

        assertEquals("explore", tool.allocateLabel("explore"));
        tool.inFlightLabels.remove("explore");
        assertEquals("explore", tool.allocateLabel("explore"), "a finished label must become available again");
    }

    @Test
    void labelsStayDistinctUnderConcurrentLaunches() throws Exception {
        var tool = new TaskTool(List.of(), null, List::of, () -> "", (a, b) -> {}, (a, b, c) -> {}, Path.of("."), 0);
        int n = 64;
        var start = new java.util.concurrent.CountDownLatch(1);
        var labels = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < n; i++) {
            var t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                labels.add(tool.allocateLabel("explore"));
            });
            t.start();
            threads.add(t);
        }
        start.countDown();
        for (var t : threads)
            t.join(10_000);

        assertEquals(n, labels.size(), "a label was handed out twice: " + labels.size() + " distinct of " + n);
    }

    /**
     * A sub-agent is a process under a tool call, the same shape as a bash command, so the same hand-off applies. It
     * was never implemented: a five-minute agent held the turn with no way out but stopping it.
     */
    @Test
    void theTaskToolAdvertisesItselfAsDetachable() {
        var tool = new TaskTool(List.of(), null, List::of, () -> "", (a, b) -> {}, (a, b, c) -> {}, Path.of("."), 0);
        assertInstanceOf(org.pijota.tools.Tool.Detachable.class, tool,
                                        "the TUI finds backgroundable tools by this interface alone");
    }

    /** Nothing running, nothing to hand off -- and never a half-built DetachedProcess. */
    @Test
    void detachDeclinesWhenNoAgentIsRunning() {
        var tool = new TaskTool(List.of(), null, List::of, () -> "", (a, b) -> {}, (a, b, c) -> {}, Path.of("."), 0);
        assertTrue(tool.detach().isEmpty());
    }

    /** Backgrounded means the jobs panel owns it; a row in both panels reports one process twice. */
    @Test
    void aBackgroundedAgentLeavesTheAgentPanel() {
        extension.onActivity("explore", "Read pom.xml");
        extension.onActivity("explore", "tokens: 2000");
        assertFalse(extension.statusLines().isEmpty(), "precondition: it is on screen");

        extension.onActivity("explore", "detached");

        assertTrue(extension.statusLines().isEmpty(), "the agent row outlived its hand-off to the job list");
    }

    @Test
    void saysSoWhenThereIsNothingToList() {
        run("");
        assertTrue(api.sentMessages().isEmpty(), "an empty list should not post a message");
        assertTrue(runtime.lastStatus().contains("no sub-agents"), runtime.lastStatus());
    }

    @Test
    void stoppingAnUnknownNameNamesWhatIsActuallyRunning() {
        run("stop nonesuch");
        assertTrue(runtime.lastStatus().contains("no sub-agents running"), runtime.lastStatus());
    }

    @Test
    void anUnrecognisedArgumentShowsTheUsageRatherThanGuessing() {
        run("wat");
        String status = runtime.lastStatus();
        assertTrue(status.contains("/agents stop"), status);
        assertTrue(api.sentMessages().isEmpty(), "a typo must not be treated as a list request");
    }

    @Test
    void killIsAcceptedAsWellAsStop() {
        run("kill all");
        assertTrue(runtime.lastStatus().contains("no sub-agents running"), runtime.lastStatus());
    }
}
