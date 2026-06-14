package ai.pijota.extensions.task;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Status-line formatting for sub-agent activity: per-agent elapsed, the parallel aggregate, and linger semantics. */
class TaskExtensionTest {

    @Test
    void parallelRunShowsAggregateAndPerAgentElapsed() {
        var ext = new TaskExtension();
        ext.onActivity("alpha", "Read x");
        ext.onActivity("beta", "Grep y");

        String all = String.join("\n", ext.statusLines());
        assertTrue(all.contains("⚙ task: 0/2 done · 2 running"), all);
        assertTrue(all.contains("task:alpha") && all.contains("task:beta"), all);
        assertTrue(all.contains("running · "), "per-agent elapsed suffix present: " + all);
    }

    @Test
    void finishedAgentLingersWhileSiblingStillRuns() {
        var ext = new TaskExtension();
        ext.onActivity("alpha", "Read x");
        ext.onActivity("beta", "Grep y");
        ext.finished("alpha", true, 1500);

        String all = String.join("\n", ext.statusLines());
        assertTrue(all.contains("✓ task:alpha"), "a finished agent stays visible while a sibling runs: " + all);
        assertTrue(all.contains("1K tokens"), "the done line shows total tokens: " + all);
        assertTrue(all.contains("⏳ task:beta"), all);
        assertTrue(all.contains("1/2 done · 1 running"), all);
    }

    @Test
    void singleAgentRunHasNoAggregateLine() {
        var ext = new TaskExtension();
        ext.onActivity("solo", "Read x");

        List<String> lines = ext.statusLines();
        assertFalse(String.join("\n", lines).contains("⚙ task:"), "no aggregate line for a single agent");
        assertEquals(1, lines.size());
    }
}
