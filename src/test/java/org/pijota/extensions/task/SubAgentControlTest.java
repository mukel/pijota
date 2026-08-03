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
 * A sub-agent is a process like any other, so it is listed and stopped like any other: {@code /jobs} shows it with an
 * id from the moment it starts and {@code /kill <id>} stops it. There is deliberately no {@code /agents} command -- a
 * second vocabulary for one kind of process was the duplication, not the cure.
 *
 * <p>
 * What is genuinely particular to sub-agents is here: the labels that keep concurrent ones apart, the spend they
 * report, and the hand-off to the background.
 */
class SubAgentControlTest {

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

    /** The whole point of an on-demand list: the status bar has already pruned these, the answer must survive. */
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

    /**
     * Telling the model not to restart a stopped agent is a request, and it was refused: after a /kill the same agent
     * came back seconds later, spending the tokens the user had just decided to stop spending. The refusal has to be
     * enforced, not asked for.
     */
    @Test
    void aStoppedAgentCannotBeRelaunchedInTheSameTurn() throws Exception {
        var sub = new SubAgent("explore", "explore things", "", List.<String>of(), null, null);
        var tool = new TaskTool(List.of(sub), null, List::of, () -> "", (a, b) -> {}, (a, b, c) -> {}, Path.of("."), 0);
        tool.abort(); // what Esc does: stops everything, and binds the refusal for this turn

        // abort() with nothing running must not arm the refusal
        assertTrue(tool.detach().isEmpty());

        tool.stopFor("explore", "you stopped this sub-agent. It will not be started again in this turn");
        var refused = tool.execute("id", new org.pijota.tools.Tool.Args(
                                        java.util.Map.of("agent", "explore", "description", "go")), ".").get();

        assertTrue(refused.isError(), refused.content());
        assertTrue(refused.content().contains("will not be started again in this turn"), refused.content());

        // the suffix a parallel batch adds is not a different agent for this purpose
        tool.stopFor("review#3", "stopped");
        assertTrue(tool.stoppedNames().contains("review"), tool.stoppedNames().toString());

        tool.clearStopped(); // a new turn
        assertFalse(tool.stoppedNames().contains("explore"), "the refusal outlived its turn");
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
}
