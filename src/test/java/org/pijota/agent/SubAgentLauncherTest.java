package org.pijota.agent;

import org.pijota.util.SelfRespawn;
import org.pijota.util.Util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentLauncherTest {

    @Test
    void agentCommandReinvokesMainOnTheJvm() {
        // Under the test JVM (HotSpot, java.home set) the launcher re-invokes org.pijota.Main via `java`.
        var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));

        assertEquals("org.pijota.Main", cmd.getLast(), cmd.toString());
        assertTrue(cmd.getFirst().endsWith("java") || cmd.getFirst().endsWith("java.exe"), cmd.getFirst());
        assertTrue(cmd.contains("--enable-preview"), cmd.toString());
    }

    @Test
    void agentCommandPassesNonBlankClasspath() {
        var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));

        int cpIndex = cmd.indexOf("-cp");
        assertTrue(cpIndex >= 0, "command should specify a classpath: " + cmd);
        String classpath = cmd.get(cpIndex + 1);
        assertFalse(classpath.isBlank());
        // The parent's own classpath is reused so the child loads the same build -- absolutised, because the child
        // starts in a worktree where a relative entry resolves to nothing (see SubAgentClasspathTest).
        assertEquals(SubAgentLauncher.absolutize(System.getProperty("java.class.path")), classpath);
    }

    @Test
    void agentCommandIsMutableSoCallerCanAppendFlags() {
        // run() appends --agent/--no-session/--model to the returned list, so it must be mutable.
        var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));
        int before = cmd.size();

        assertDoesNotThrow(() -> cmd.add("--agent"));
        assertEquals(before + 1, cmd.size());
    }

    /**
     * When java.class.path is empty the launcher falls back to the build output it can find. The fallback used to
     * accept {@code "."} as a pass, which is the value returned when every branch has failed: it looked for
     * {@code ai/pijota/Main.class} after the package moved to {@code org.pijota}, so no branch could match and the test
     * still went green. The child would then have launched with a classpath containing no pijota at all.
     */
    @Test
    void resolveClasspathFindsARealBuildWhenThereIsNoParentClasspath() {
        String saved = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", "");
            var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));
            String classpath = cmd.get(cmd.indexOf("-cp") + 1);

            assertNotEquals(".", classpath, "'.' is the give-up value, not a fallback");
            String first = classpath.split(File.pathSeparator, 2)[0];
            assertTrue(first.endsWith(".jar") || Files.exists(Path.of(first).resolve("org/pijota/Main.class")),
                                            "the child must be able to load Main from it: " + classpath);
        } finally {
            System.setProperty("java.class.path", saved);
        }
    }

    /**
     * A sub-agent is a fresh JVM built from scratch, so it inherits no system property unless one is passed. Without
     * this it read {@code ~/.pijota} while its parent used the home it was pointed at, and wrote its logs, sessions and
     * trust decisions there.
     */
    @Test
    void agentCommandCarriesSystemPropertiesToTheChild() {
        var expected = SelfRespawn.jvmOptions();
        // From surefire's argLine. Asserting against an empty list would pass however the command is built.
        assertFalse(expected.isEmpty(), "the test JVM must be started with a -D for this to mean anything");

        var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));
        assertTrue(cmd.containsAll(expected), "every -D the parent got must reach the child: " + cmd);
        // and they must precede -cp and the main class, or java reads them as program arguments
        for (String option : expected)
            assertTrue(cmd.indexOf(option) < cmd.indexOf("-cp"), option + " came after -cp: " + cmd);
    }

    @Test
    void specRetainsAllFields() {
        var spec = new SubAgentLauncher.Spec("do the thing", "be brief", List.of("read", "grep"), "/work", "openai",
                                        "gpt-4o", "secret", Duration.ofMinutes(2));

        assertEquals("do the thing", spec.task());
        assertEquals("be brief", spec.systemPrompt());
        assertEquals(List.of("read", "grep"), spec.tools());
        assertEquals("/work", spec.cwd());
        assertEquals("openai", spec.provider());
        assertEquals("gpt-4o", spec.model());
        assertEquals("secret", spec.apiKey());
        assertEquals(Duration.ofMinutes(2), spec.timeout());
    }

    @Test
    void buildTaskJsonIncludesAllProvidedFields() throws Exception {
        var spec = new SubAgentLauncher.Spec("task", "sys", List.of("read", "grep"), "/work", "openai", "gpt-4o", "key",
                                        Duration.ofMinutes(1));
        var json = Util.MAPPER.readValue(SubAgentLauncher.buildTaskJson(spec), Map.class);

        assertEquals("task", json.get("description"));
        assertEquals("sys", json.get("systemPrompt"));
        assertEquals(List.of("read", "grep"), json.get("tools"));
        assertEquals("/work", json.get("cwd"));
        assertEquals("openai", json.get("provider"));
        assertEquals("gpt-4o", json.get("model"));
        assertEquals("key", json.get("apiKey"));
        assertEquals(1, json.get("subagentDepth"));
    }

    @Test
    void buildTaskJsonOmitsBlankAndEmptyOptionals() throws Exception {
        var spec = new SubAgentLauncher.Spec("task", "  ", List.of(), "/work", null, null, "", null);
        var json = Util.MAPPER.readValue(SubAgentLauncher.buildTaskJson(spec), Map.class);

        assertEquals("task", json.get("description"));
        assertEquals("/work", json.get("cwd"));
        assertFalse(json.containsKey("systemPrompt"));
        assertFalse(json.containsKey("tools"));
        assertFalse(json.containsKey("provider"));
        assertFalse(json.containsKey("model"));
        assertFalse(json.containsKey("apiKey"));
    }

    @Test
    void resolveOutputReturnsDoneEventOutput() throws Exception {
        var out = SubAgentLauncher.resolveOutput(List.of("{\"type\":\"done\",\"output\":\"the answer\"}"), 0, "");
        assertEquals("the answer", out);
    }

    @Test
    void resolveOutputThrowsTheReportedError() {
        var ex = assertThrows(IOException.class, () -> SubAgentLauncher.resolveOutput(
                                        List.of("{\"type\":\"done\",\"output\":\"\",\"error\":\"boom\"}"), 0, ""));
        assertEquals("boom", ex.getMessage());
    }

    @Test
    void resolveOutputThrowsOnBlankOutputWithNonZeroExit() {
        var ex = assertThrows(IOException.class,
                                        () -> SubAgentLauncher.resolveOutput(List.of(), 1, "stack trace here"));
        assertTrue(ex.getMessage().contains("exit 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("stack trace here"), ex.getMessage());
    }

    @Test
    void resolveOutputReturnsBlankWhenNoDoneAndCleanExit() throws Exception {
        assertEquals("", SubAgentLauncher.resolveOutput(List.of("not json", "{\"type\":\"stream\"}"), 0, ""));
    }
}
