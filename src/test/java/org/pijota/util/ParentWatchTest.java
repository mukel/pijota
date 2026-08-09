package org.pijota.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ParentWatchTest {

    /**
     * Only a worker dies with its parent. Anything else was started from a shell, and a shell exiting is not a reason
     * to stop -- a headless run piped from a script would take its own launcher's exit as an order to quit.
     */
    @Test
    void onlyAWorkerWithAKnownParentIsWatched() {
        var parent = ProcessHandle.current().parent();
        assertFalse(ParentWatch.shouldWatch(false, parent), "not a worker: nothing to die with");
        assertFalse(ParentWatch.shouldWatch(true, Optional.empty()), "no parent to watch (pid 1, or unavailable)");
        assertEquals(parent.isPresent(), ParentWatch.shouldWatch(true, parent));
        assertFalse(ParentWatch.arm(false), "arming is a no-op unless this is a supervised worker");
    }

    /**
     * The whole point, end to end: a real process is started, watched, killed outright, and the watcher fires. Killed
     * with SIGKILL specifically, because that is the case no shutdown hook on the other side can cover.
     */
    @Test
    void aParentKilledOutrightStillTriggersTheWatch() throws Exception {
        var victim = new ProcessBuilder("sleep", "60").start();
        var fired = new AtomicBoolean();

        assertTrue(ParentWatch.arm(true, Optional.of(victim.toHandle()), () -> fired.set(true)));
        assertFalse(fired.get(), "nothing has happened yet");

        victim.destroyForcibly();
        assertTrue(victim.waitFor(10, TimeUnit.SECONDS), "the victim should be gone");
        // onExit completes off this thread; give it a bounded moment rather than sleeping a fixed guess.
        for (int i = 0; i < 100 && !fired.get(); i++)
            Thread.sleep(50);
        assertTrue(fired.get(), "the watch must fire when the parent is killed, not merely when it exits politely");
    }
}
