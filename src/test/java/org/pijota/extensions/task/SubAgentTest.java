package org.pijota.extensions.task;

import org.pijota.config.ProjectTrust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentTest {

    @Test
    void loadJsonReadsTheAgentDefinition(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("coder.json");
        Files.writeString(f, "{\"name\":\"coder\",\"description\":\"writes code\",\"tools\":[\"read\",\"edit\"],"
                                        + "\"model\":\"gpt-x\"}");
        var agent = SubAgent.loadJson(f, "user");
        assertEquals("coder", agent.name());
        assertEquals("writes code", agent.description());
        assertEquals(List.of("read", "edit"), agent.tools());
        assertEquals("gpt-x", agent.model());
    }

    @Test
    void loadJsonOnMalformedFileYieldsAFailedPlaceholder(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("broken.json");
        Files.writeString(f, "{ not json");
        var agent = SubAgent.loadJson(f, "user");
        assertEquals("broken", agent.name());
        assertTrue(agent.description().startsWith("failed:"), agent.description());
    }

    @Test
    void loadAllIncludesBundledBuiltinAgents(@TempDir Path cwd) {
        // Loaded by name from resources (getResourceAsStream), not by enumerating the classpath "agents" directory,
        // so the built-ins are present in a native image, where that directory can't be listed.
        var builtins = SubAgent.loadAll(cwd).stream().filter(a -> "builtin".equals(a.source())).map(SubAgent::name)
                                        .toList();
        assertTrue(builtins.contains("explore"), builtins.toString());
        assertTrue(builtins.contains("summarize"), builtins.toString());
    }

    @Test
    void loadAllPicksUpProjectJsonAgentsAndAppliesOverrides(@TempDir Path cwd) throws Exception {
        ProjectTrust.resolve(cwd.toString(), true);
        Path agentsDir = cwd.resolve(".pijota/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("coder.json"), "{\"name\":\"coder\",\"model\":\"gpt-x\"}");
        Files.writeString(agentsDir.resolve("tester.json"), "{\"name\":\"tester\"}");
        Files.writeString(cwd.resolve(".pijota/subagents.json"), "{\"agentOverrides\":{\"tester\":{\"disabled\":true},"
                                        + "\"coder\":{\"model\":\"override-model\"}}}");

        var agents = SubAgent.loadAll(cwd);

        var coder = agents.stream().filter(a -> a.name().equals("coder")).findFirst().orElseThrow();
        assertEquals("override-model", coder.model(), "the override is applied");
        assertTrue(agents.stream().noneMatch(a -> a.name().equals("tester")), "the disabled agent is removed");
    }

    /**
     * The user's agents directory took {@code *.json} only, while every doc told users to write {@code <name>.md}. A
     * correct markdown agent dropped into the documented path simply never appeared, and nothing said why.
     */
    @Test
    void userAgentsDirectoryReadsMarkdown(@TempDir Path home, @TempDir Path cwd) throws Exception {
        withHome(home, () -> {
            Files.createDirectories(home.resolve("agents"));
            Files.writeString(home.resolve("agents/reviewer.md"),
                                            "---\nname: reviewer\ndescription: reviews code\n---\nbody");

            var agent = SubAgent.loadAll(cwd).stream().filter(a -> a.name().equals("reviewer")).findFirst()
                                            .orElseThrow(() -> new AssertionError("markdown agent was not loaded"));
            assertEquals("reviews code", agent.description());
            assertEquals("user", agent.source());
        });
    }

    /** JSON kept working alongside it: this directory took only JSON for a long time. */
    @Test
    void userAgentsDirectoryStillReadsJson(@TempDir Path home, @TempDir Path cwd) throws Exception {
        withHome(home, () -> {
            Files.createDirectories(home.resolve("agents"));
            Files.writeString(home.resolve("agents/coder.json"), "{\"name\":\"coder\",\"description\":\"writes\"}");
            assertTrue(SubAgent.loadAll(cwd).stream().anyMatch(a -> a.name().equals("coder")));
        });
    }

    /** A user file of a built-in's name replaces it, the way a skill or theme of that name already does. */
    @Test
    void userAgentShadowsTheBuiltinOfTheSameName(@TempDir Path home, @TempDir Path cwd) throws Exception {
        withHome(home, () -> {
            Files.createDirectories(home.resolve("agents"));
            Files.writeString(home.resolve("agents/explore.md"), "---\nname: explore\ndescription: mine\n---\nbody");

            var explores = SubAgent.loadAll(cwd).stream().filter(a -> a.name().equals("explore")).toList();
            assertEquals(1, explores.size(), "the built-in must be replaced, not duplicated: " + explores);
            assertEquals("mine", explores.getFirst().description());
        });
    }

    /**
     * A sub-agent's description reaches the model in the task tool's schema and its prompt drives a sub-process with
     * full tools, so a freshly cloned repository must not supply one before the user has trusted the folder.
     */
    @Test
    void projectAgentsDoNotLoadUntilTheFolderIsTrusted(@TempDir Path cwd) throws Exception {
        Files.createDirectories(cwd.resolve(".pijota/agents"));
        Files.writeString(cwd.resolve(".pijota/agents/deploy.md"), "---\nname: deploy\ndescription: theirs\n---\nbody");

        ProjectTrust.resolve(cwd.toString(), false);
        assertTrue(SubAgent.loadAll(cwd).stream().noneMatch(a -> a.name().equals("deploy")), "untrusted");

        ProjectTrust.resolve(cwd.toString(), true);
        assertTrue(SubAgent.loadAll(cwd).stream().anyMatch(a -> a.name().equals("deploy")), "trusted");
    }

    /** The flag is named for what it disables; it used to delete the user's own agents and keep every built-in. */
    @Test
    void disableBuiltinsRemovesBuiltinsAndKeepsTheUsersOwn(@TempDir Path home, @TempDir Path cwd) throws Exception {
        withHome(home, () -> {
            Files.createDirectories(home.resolve("agents"));
            Files.writeString(home.resolve("agents/mine.md"), "---\nname: mine\ndescription: d\n---\nbody");
            Files.writeString(home.resolve("subagents.json"), "{\"disableBuiltins\":true}");

            var names = SubAgent.loadAll(cwd).stream().map(SubAgent::name).toList();
            assertTrue(names.contains("mine"), names.toString());
            assertFalse(names.contains("explore"), names.toString());
        });
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void withHome(Path home, Body body) throws Exception {
        String saved = System.setProperty("pijota.home", home.toString());
        try {
            body.run();
        } finally {
            if (saved == null)
                System.clearProperty("pijota.home");
            else
                System.setProperty("pijota.home", saved);
        }
    }

    @Test
    void recordStoresFields() {
        var agent = new SubAgent("test", "desc", "prompt", List.of("read", "write"), "gpt-4", "project");
        assertEquals("test", agent.name());
        assertEquals("desc", agent.description());
        assertEquals("prompt", agent.systemPrompt());
        assertEquals(List.of("read", "write"), agent.tools());
        assertEquals("gpt-4", agent.model());
        assertEquals("project", agent.source());
    }

    @Test
    void recordEquality() {
        var a = new SubAgent("n", "d", "s", List.of(), null, "src");
        var b = new SubAgent("n", "d", "s", List.of(), null, "src");
        assertEquals(a, b);
        assertNotEquals(a, new SubAgent("x", "d", "s", List.of(), null, "src"));
    }

    @Test
    void toolsFromMapWithList() {
        var tools = SubAgent.toolsFromMap(List.of("read", "write"));
        assertEquals(List.of("read", "write"), tools);
    }

    @Test
    void toolsFromMapWithCommaSeparatedString() {
        var tools = SubAgent.toolsFromMap("read, write , edit");
        assertEquals(3, tools.size());
        assertTrue(tools.contains("read"));
        assertTrue(tools.contains("write"));
        assertTrue(tools.contains("edit"));
    }

    @Test
    void toolsFromMapWithSemicolonSeparated() {
        var tools = SubAgent.toolsFromMap("read;write;glob");
        assertEquals(3, tools.size());
    }

    @Test
    void toolsFromMapWithNull() {
        assertTrue(SubAgent.toolsFromMap(null).isEmpty());
    }

    @Test
    void toolsFromMapWithEmptyList() {
        assertTrue(SubAgent.toolsFromMap(List.of()).isEmpty());
    }

    @Test
    void markdownModelNullLiteralBecomesNullNotTheStringNull(@TempDir Path dir) throws Exception {
        // `model: null` means "no override, inherit parent"; must NOT become the literal model "null" (Codex 400s on
        // it).
        var file = dir.resolve("summarize.md");
        Files.writeString(file, "---\nname: summarize\nmodel: null\n---\nbody");
        assertNull(SubAgent.loadMarkdown(file, "test").model());
    }

    @Test
    void markdownConcreteModelIsKept(@TempDir Path dir) throws Exception {
        var file = dir.resolve("fast.md");
        Files.writeString(file, "---\nname: fast\nmodel: gpt-5.5\n---\nbody");
        assertEquals("gpt-5.5", SubAgent.loadMarkdown(file, "test").model());
    }

    @Test
    void toolsFromMapWithBlankString() {
        var tools = SubAgent.toolsFromMap("read,  , write");
        assertEquals(2, tools.size());
    }

    @Test
    void stringHelperWithString() {
        assertEquals("value", SubAgent.string(Map.of("key", "value"), "key", "default"));
    }

    @Test
    void stringHelperWithBlankString() {
        assertEquals("default", SubAgent.string(Map.of("key", "  "), "key", "default"));
    }

    @Test
    void stringHelperWithNonString() {
        assertEquals("default", SubAgent.string(Map.of("key", 42), "key", "default"));
    }

    @Test
    void stringHelperWithMissingKey() {
        assertEquals("fallback", SubAgent.string(Map.of(), "key", "fallback"));
    }
}
