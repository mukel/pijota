package org.pijota.extensions.task;

import org.pijota.testkit.FauxLlm;
import org.pijota.tools.Tool;
import org.pijota.util.Env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TaskToolTest {

    @TempDir
    Path orchTempDir;

    private static final SubAgent ALPHA = new SubAgent("alpha", "d", "", List.of(), null, "test");
    private static final SubAgent BETA = new SubAgent("beta", "d", "", List.of(), null, "test");

    private TaskTool toolWith(List<SubAgent> agents, TaskTool.AgentRunner runner) {
        var tool = new TaskTool(agents, null, List::of, () -> "", (a, l) -> {}, (a, s, t) -> {}, orchTempDir, 0);
        tool.setAgentRunner(runner); // no real sub-process spawn — canned results drive the orchestration
        return tool;
    }

    @Test
    void renderResultFormatsSingleChainAndLoggedResults() {
        var tool = new TaskTool(List.of(ALPHA), null, List::of, () -> "", (a, l) -> {}, (a, s, t) -> {}, orchTempDir,
                                        0);

        // single agent with a description → summary is the description, output becomes body lines
        var single = tool.renderResult(new Tool.Args(Map.of("agent", "alpha", "description", "do the thing")),
                                        Tool.ToolResult.success("line A\nline B"), orchTempDir.toString());
        assertEquals("do the thing", single.summary());
        assertFalse(single.body().isEmpty());

        // single agent without a description → summary derives from the output's first line
        var noDesc = tool.renderResult(new Tool.Args(Map.of("agent", "alpha")),
                                        Tool.ToolResult.success("first line\nsecond line"), orchTempDir.toString());
        assertTrue(noDesc.summary().contains("first line"), noDesc.summary());

        // chain/parallel (has "chain") → summary is the first output line
        var chain = tool.renderResult(new Tool.Args(Map.of("chain", List.of())),
                                        Tool.ToolResult.success("chain summary\ndetail line"), orchTempDir.toString());
        assertEquals("chain summary", chain.summary());

        // a result carrying logFiles → a "full output" footer line per log
        var withLogs = tool.renderResult(new Tool.Args(Map.of("agent", "alpha", "description", "x")),
                                        Tool.ToolResult.success("out", Map.of("logFiles",
                                                                        List.of(Map.of("path", "/tmp/sub.log", "agent",
                                                                                                        "alpha")))),
                                        orchTempDir.toString());
        assertTrue(withLogs.body().stream().anyMatch(l -> l.contains("full output")), withLogs.body().toString());
    }

    @Test
    @Timeout(60)
    void realSpawnProducesAResultWithoutHanging() throws Exception {
        // No setAgentRunner → exercises the real spawnAgent (reinvokes the JVM as `Main --agent`). A keyless FauxLlm
        // means the spawned sub-agent fails fast, so this verifies the spawn/stream/result-collection path produces a
        // result (typically an error) rather than hanging — without a working LLM.
        var tool = new TaskTool(List.of(ALPHA), new FauxLlm(), List::of, () -> "", (a, l) -> {}, (a, s, t) -> {},
                                        orchTempDir, 0);
        // Isolate the sub-agent log dir into the test's @TempDir so the spawn doesn't pollute the shared
        // tmpdir/pijota-logs that the log-picker tests scan.
        System.setProperty("pijota.log.dir", orchTempDir.resolve("logs").toString());
        try {
            var result = tool.execute("id", new Tool.Args(Map.of("agent", "alpha", "description", "x")),
                                            orchTempDir.toString()).get(45, TimeUnit.SECONDS);
            assertNotNull(result, "the real spawn path always yields a ToolResult");
        } finally {
            System.clearProperty("pijota.log.dir");
        }
    }

    @Test
    void singleModeRunsTheNamedAgentAndReturnsItsOutput() {
        var tool = toolWith(List.of(ALPHA), (s, task, wd) -> new TaskTool.SubAgentResult(s.name(), "ran: " + task, 0, 1,
                                        2, null, null));
        var result = run(tool, Map.of("agent", "alpha", "description", "do it"), orchTempDir);
        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains("ran: do it"), result.content());
        assertEquals("single", result.details().get("mode"));
    }

    @Test
    void singleModeReportsAnUnknownAgent() {
        var result = run(toolWith(List.of(), (s, t, w) -> null), Map.of("agent", "ghost", "description", "x"),
                                        orchTempDir);
        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"), result.content());
    }

    @Test
    void parallelModeRunsEveryTaskAndAggregates() {
        var tool = toolWith(List.of(ALPHA, BETA), (s, task, wd) -> new TaskTool.SubAgentResult(s.name(),
                                        "out-" + s.name(), 0, 1, 1, null, null));
        var result = run(tool, Map.of("tasks",
                                        List.of(Map.of("agent", "alpha", "description", "t1"),
                                                                        Map.of("agent", "beta", "description", "t2"))),
                                        orchTempDir);
        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains("2/2 succeeded"), result.content());
        assertTrue(result.content().contains("out-alpha") && result.content().contains("out-beta"), result.content());
        assertEquals(2, result.details().get("resultCount"));
    }

    @Test
    void parallelModeRunsAgentsInGitWorktreesWhenRequested() throws Exception {
        assumeTrue(gitAvailable(), "git not installed");
        initGitRepo(orchTempDir);
        var seenDirs = Collections.synchronizedList(new ArrayList<String>());
        var tool = toolWith(List.of(ALPHA, BETA), (s, task, wd) -> {
            seenDirs.add(wd);
            return new TaskTool.SubAgentResult(s.name(), "ok", 0, 1, 1, null, null);
        });

        var result = run(tool, Map.of("worktree", true, "tasks",
                                        List.of(Map.of("agent", "alpha", "description", "t1"),
                                                                        Map.of("agent", "beta", "description", "t2"))),
                                        orchTempDir);

        assertFalse(result.isError(), result.content());
        assertTrue(seenDirs.stream().anyMatch(d -> d.contains("wt-")), "agents run in per-task worktrees: " + seenDirs);
    }

    @Test
    void renderResultSingleModeSummarizesFromTheDescription() {
        var tool = toolWith(List.of(), (s, t, w) -> null);
        var result = Tool.ToolResult.success("output line one\noutput line two");
        var view = tool.renderResult(new Tool.Args(Map.of("description", "build the feature")), result,
                                        orchTempDir.toString());
        assertTrue(view.summary().contains("build the feature"), view.summary());
        assertFalse(view.body().isEmpty(), "the output preview lines are shown");
    }

    @Test
    void renderResultMultiModeSummarizesFromTheFirstLine() {
        var tool = toolWith(List.of(), (s, t, w) -> null);
        var result = Tool.ToolResult.success("Parallel: 2/2 succeeded\nalpha ok\nbeta ok");
        var view = tool.renderResult(new Tool.Args(Map.of("tasks", List.of())), result, orchTempDir.toString());
        assertTrue(view.summary().contains("Parallel"), view.summary());
    }

    @Test
    void handleSubAgentEventParsesEachEventType() throws Exception {
        var activity = new ArrayList<String>();
        var finished = new ArrayList<String>();
        var tool = new TaskTool(List.of(), null, List::of, () -> "", (a, l) -> activity.add(l),
                                        (a, ok, tok) -> finished.add(a + ":" + ok + ":" + tok), orchTempDir, 0);
        var holder = new TaskTool.SubAgentResult[1];
        var writer = new BufferedWriter(new StringWriter());
        var counter = new AtomicInteger();

        tool.handleSubAgentEvent("{\"type\":\"tool_start\",\"tool\":\"read\",\"args\":\"{\\\"path\\\":\\\"a/b.txt\\\"}\"}",
                                        "alpha", writer, "/tmp/a.log", holder, counter);
        assertEquals(1, counter.get(), "tool_start bumps the tool counter");

        tool.handleSubAgentEvent("{\"type\":\"done\",\"output\":\"all good\",\"exitCode\":0,\"turns\":2,\"toolCount\":3,"
                                        + "\"tokens\":150}", "alpha", writer, "/tmp/a.log", holder, counter);
        assertNotNull(holder[0]);
        assertEquals("all good", holder[0].output());
        assertEquals(0, holder[0].exitCode());
        assertEquals("alpha:true:150", finished.getFirst(), "done fires the finish callback with tokens");
    }

    @Test
    void handleSubAgentEventOnMalformedLineYieldsAnErrorResult() throws Exception {
        var tool = new TaskTool(List.of(), null, List::of, () -> "", (a, l) -> {}, (a, ok, tok) -> {}, orchTempDir, 0);
        var holder = new TaskTool.SubAgentResult[1];
        var writer = new BufferedWriter(new StringWriter());

        tool.handleSubAgentEvent("this is not json", "alpha", writer, "/tmp/a.log", holder, new AtomicInteger());

        assertNotNull(holder[0], "a parse failure still produces a (failed) result, never a lost turn");
        assertEquals(1, holder[0].exitCode());
        assertNotNull(holder[0].error());
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void initGitRepo(Path dir) throws Exception {
        git(dir, "init", "-q");
        git(dir, "config", "user.email", "t@t");
        git(dir, "config", "user.name", "t");
        Files.writeString(dir.resolve("f.txt"), "x");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "init"); // worktree add needs a committed HEAD
    }

    private static void git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
    }

    @Test
    void chainModeStopsAtTheFirstFailure() {
        var tool = toolWith(List.of(ALPHA, BETA), (s, task, wd) -> s.name().equals("beta")
                                        ? new TaskTool.SubAgentResult("beta", "", 1, 1, 0, null, "boom")
                                        : new TaskTool.SubAgentResult("alpha", "step1 done", 0, 1, 1, null, null));
        var result = run(tool, Map.of("chain",
                                        List.of(Map.of("agent", "alpha", "description", "first"), Map.of("agent",
                                                                        "beta", "description", "use {previous}"))),
                                        orchTempDir);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Chain stopped"), result.content());
    }

    @Test
    void chainModeRunsAllStepsOnSuccess() {
        var tool = toolWith(List.of(ALPHA, BETA), (s, task, wd) -> new TaskTool.SubAgentResult(s.name(),
                                        "done-" + s.name(), 0, 1, 1, null, null));
        var result = run(tool, Map.of("chain",
                                        List.of(Map.of("agent", "alpha", "description", "a"),
                                                                        Map.of("agent", "beta", "description", "b"))),
                                        orchTempDir);
        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains("done-beta"), "the final step's output is returned: " + result.content());
    }

    @Test
    void resolveCurrentAliasMirrorsParentModel() {
        assertEquals("anthropic/claude-sonnet-4-6", TaskTool.resolveCurrentAlias("alias/current", "anthropic",
                                        "claude-sonnet-4-6"));
    }

    @Test
    void resolveCurrentAliasLeavesOtherModelsUnchanged() {
        assertEquals("alias/fast", TaskTool.resolveCurrentAlias("alias/fast", "anthropic", "claude-sonnet-4-6"));
        assertEquals("openai/gpt-4o", TaskTool.resolveCurrentAlias("openai/gpt-4o", "anthropic", "x"));
        assertNull(TaskTool.resolveCurrentAlias(null, "anthropic", "x"));
    }

    @Test
    void subAgentModelPrefersPerAgentOverride() {
        var sub = new SubAgent("a", "d", "p", List.of(), "claude-haiku-4-5", "builtin");
        assertEquals("claude-haiku-4-5", TaskTool.subAgentModel(sub));
    }

    @Test
    void subAgentModelFallsBackToGlobalRoutingProperty() {
        var sub = new SubAgent("a", "d", "p", List.of(), null, "builtin");
        String prev = System.getProperty("pijota.subagent.model");
        System.setProperty("pijota.subagent.model", "gpt-5.4-mini");
        try {
            assertEquals("gpt-5.4-mini", TaskTool.subAgentModel(sub));
        } finally {
            if (prev == null)
                System.clearProperty("pijota.subagent.model");
            else
                System.setProperty("pijota.subagent.model", prev);
        }
    }

    @Test
    void subAgentModelNullInheritsParent() {
        var sub = new SubAgent("a", "d", "p", List.of(), null, "builtin");
        // No per-agent override and no routing property set → inherit parent (null).
        if (Env.get("pijota.subagent.model", "PIJOTA_SUBAGENT_MODEL") == null)
            assertNull(TaskTool.subAgentModel(sub));
    }

    @Test
    void formatToolCallRead() {
        var result = TaskTool.formatToolCall("read", "{\"filePath\":\"file.txt\"}");
        assertTrue(result.contains("Read") || result.contains("read"), "should indicate read: " + result);
        assertTrue(result.contains("file.txt"), "should include path: " + result);
    }

    @Test
    void formatToolCallWrite() {
        var result = TaskTool.formatToolCall("write", "{\"filePath\":\"out.txt\"}");
        assertTrue(result.contains("Write") || result.contains("write"), "should indicate write: " + result);
        assertTrue(result.contains("out.txt"), "should include path: " + result);
    }

    @Test
    void formatToolCallEdit() {
        var result = TaskTool.formatToolCall("edit", "{\"filePath\":\"src/Foo.java\"}");
        assertTrue(result.contains("Edit") || result.contains("edit"), "should indicate edit: " + result);
        assertTrue(result.contains("Foo.java"), "should include path: " + result);
    }

    @Test
    void formatToolCallBash() {
        var result = TaskTool.formatToolCall("bash", "{\"command\":\"ls -la\"}");
        assertTrue(result.contains("ls -la"), "should contain command: " + result);
    }

    @Test
    void formatToolCallGrep() {
        var result = TaskTool.formatToolCall("grep", "{\"pattern\":\"TODO\",\"filePath\":\"src/\"}");
        assertTrue(result.contains("Grep") || result.contains("grep"), "should indicate grep: " + result);
        assertTrue(result.contains("TODO"), "should include pattern: " + result);
    }

    @Test
    void formatToolCallGlob() {
        var result = TaskTool.formatToolCall("glob", "{\"pattern\":\"*.java\"}");
        assertTrue(result.contains("Glob") || result.contains("glob"), "should indicate glob: " + result);
    }

    @Test
    void formatToolCallLs() {
        var result = TaskTool.formatToolCall("ls", "{\"path\":\"src/\"}");
        assertTrue(result.contains("Ls") || result.contains("List"), "should indicate ls: " + result);
    }

    @Test
    void formatToolCallUnknown() {
        var result = TaskTool.formatToolCall("custom", "{\"x\":\"y\"}");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void formatToolCallInvalidJson() {
        var result = TaskTool.formatToolCall("read", "not json");
        assertTrue(result.contains("read"));
    }

    @Test
    void sanitizeReplacesUnsafeChars() {
        assertEquals("a_b_c", TaskTool.sanitize("a b/c"));
        assertEquals("ok-name_1.2", TaskTool.sanitize("ok-name_1.2"));
    }

    @Test
    void fileExtractsBasenameAcrossKeyAliasesOrDefaultsToDot() {
        assertEquals("z.txt", TaskTool.file(Map.of("path", "x/y/z.txt")));
        assertEquals("f.java", TaskTool.file(Map.of("file_path", "a/f.java")));
        assertEquals(".", TaskTool.file(Map.of()));
    }

    @Test
    void detailsForResultsSummarizesModeCountsAndLogs() {
        var ok = new TaskTool.SubAgentResult("alpha", "out", 0, 1, 2, "/tmp/alpha.log", null);
        var bad = new TaskTool.SubAgentResult("beta", "", 1, 1, 0, null, "boom");
        var details = TaskTool.detailsForResults("parallel", List.of(ok, bad));
        assertEquals("parallel", details.get("mode"));
        assertEquals(2, details.get("resultCount"));
        assertEquals(1L, details.get("succeeded"));
        assertTrue(details.containsKey("logFiles"), details.toString());
    }
}
