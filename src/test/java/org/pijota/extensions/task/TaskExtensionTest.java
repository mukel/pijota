package org.pijota.extensions.task;

import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.testkit.FauxLlm;
import org.pijota.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pijota.tools.ToolGlyphs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Status-line formatting for sub-agent activity: per-agent elapsed, the parallel aggregate, and linger semantics. */
class TaskExtensionTest {

    @Test
    void logsCommandPicksAndOpensSubAgentLogs(@TempDir Path tmp) throws Exception {
        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(tmp);
        runtime.setToolRegistry(new ToolRegistry());
        runtime.setLlm(new FauxLlm());
        var fake = new FakeExtensionAPI(runtime);
        var ext = new TaskExtension();
        ext.init(fake);
        Path log = tmp.resolve("sub-alpha.log");
        Files.writeString(log, "tool: read\nline of output\n");
        ext.onActivity("alpha", "log: " + log); // register it in activeLogs (collectLogs picks it up)

        assertDoesNotThrow(() -> {
            fake.slashCommand("logs").handler().accept(""); // picker / static list
            fake.slashCommand("logs").handler().accept("latest"); // open the latest
            fake.slashCommand("logs").handler().accept("sub-alpha"); // open by name match
            fake.slashCommand("logs").handler().accept("1"); // open by index
            fake.slashCommand("logs").handler().accept("nomatch"); // no match -> status
        });
    }

    @Test
    void parallelRunShowsAggregateAndPerAgentElapsed() {
        var ext = new TaskExtension();
        ext.onActivity("alpha", "Read x");
        ext.onActivity("beta", "Grep y");

        String all = String.join("\n", ext.statusLines());
        assertTrue(all.contains(ToolGlyphs.AGENT + " task: 0/2 done · 2 running"), all);
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
        assertTrue(all.contains(ToolGlyphs.DONE + " task:alpha"),
                                        "a finished agent stays visible while a sibling runs: " + all);
        assertTrue(all.contains("1K tokens"), "the done line shows total tokens: " + all);
        assertTrue(all.contains(ToolGlyphs.AGENT + " task:beta"), all);
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

    @Test
    void aFailedAgentShowsTheErrorIcon() {
        var ext = new TaskExtension();
        ext.onActivity("solo", "Bash boom");
        ext.finished("solo", false, 500);

        String all = String.join("\n", ext.statusLines());
        // ✗ here and ✖ on a failed tool call were two different failure marks for the same idea; there is one now.
        assertTrue(all.contains(ToolGlyphs.FAILED + " task:solo"), "a failed agent carries the failure mark: " + all);
    }

    @Test
    void noAgentsMeansNoStatusLines() {
        assertTrue(new TaskExtension().statusLines().isEmpty(), "nothing running → no status");
    }

    @Test
    void nameAndDescriptionAreSet() {
        var ext = new TaskExtension();
        assertEquals("task", ext.name());
        assertFalse(ext.description().isBlank());
    }

    @Test
    void activityUpdatesTheRunningAgentsLine() {
        var ext = new TaskExtension();
        ext.onActivity("alpha", "Read one");
        ext.onActivity("alpha", "Edit two"); // a second activity for the same agent refreshes its line
        String all = String.join("\n", ext.statusLines());
        assertTrue(all.contains("task:alpha"), all);
        assertTrue(all.contains("running"), all);
    }
}
