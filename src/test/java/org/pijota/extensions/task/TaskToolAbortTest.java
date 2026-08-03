package org.pijota.extensions.task;

import org.pijota.util.ProcessRunner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sub-agent is a process spending tokens, and interrupting the turn has to reach it. Nothing did: TaskTool never
 * overrode {@code Tool.abort()}, so pressing Esc left every child running to its own five-minute cap, paying for output
 * the parent had already stopped listening for.
 *
 * <p>
 * These exercise the mechanism against a real process rather than the tool, because the thing worth proving is that a
 * handle captured through {@code onStart} can still be killed, together with its own descendants -- an agent that has
 * itself launched a build or a browser must not outlive the agent.
 */
class TaskToolAbortTest {

    /** What TaskTool does: collect live children through onStart, then destroy the tree on abort. */
    private record Supervisor(Set<Process> running) {
        Supervisor() {
            this(ConcurrentHashMap.newKeySet());
        }

        void abort() {
            for (Process p : running) {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
            }
            running.clear();
        }
    }

    @Test
    void abortKillsAChildCapturedThroughOnStart() throws Exception {
        var supervisor = new Supervisor();
        var child = new AtomicReference<Process>();

        var run = CompletableFuture.supplyAsync(() -> {
            try {
                return ProcessRunner.command(java.util.List.of("sleep", "60")).timeout(Duration.ofMinutes(5))
                                                .onStart(p -> {
                                                    child.set(p);
                                                    supervisor.running().add(p);
                                                }).run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        for (int i = 0; i < 200 && child.get() == null; i++)
            Thread.sleep(10);
        assertNotNull(child.get(), "the process never started");
        assertTrue(child.get().isAlive());

        supervisor.abort();

        assertTrue(child.get().waitFor(10, TimeUnit.SECONDS), "abort did not stop the child");
        assertFalse(child.get().isAlive());
        run.get(20, TimeUnit.SECONDS); // the runner unblocks rather than hanging on a killed child
        assertTrue(supervisor.running().isEmpty(), "the set must not keep dead handles");
    }

    /** An agent that spawned its own bash must not leave it behind; that is why abort walks descendants first. */
    @Test
    void abortReachesTheChildsOwnChildren() throws Exception {
        var supervisor = new Supervisor();
        var child = new AtomicReference<Process>();

        CompletableFuture.runAsync(() -> {
            try {
                ProcessRunner.command(java.util.List.of("sh", "-c", "sleep 60 & wait")).timeout(Duration.ofMinutes(5))
                                                .onStart(p -> {
                                                    child.set(p);
                                                    supervisor.running().add(p);
                                                }).run();
            } catch (Exception ignored) {
                // the kill below is the point; how the runner reports it is not
            }
        });

        for (int i = 0; i < 200 && (child.get() == null || child.get().descendants().findAny().isEmpty()); i++)
            Thread.sleep(10);
        var grandchildren = child.get().descendants().toList();
        assertFalse(grandchildren.isEmpty(), "no grandchild to test against");

        supervisor.abort();

        for (ProcessHandle g : grandchildren) {
            g.onExit().get(10, TimeUnit.SECONDS);
            assertFalse(g.isAlive(), "a grandchild outlived the abort: " + g.pid());
        }
    }
}
