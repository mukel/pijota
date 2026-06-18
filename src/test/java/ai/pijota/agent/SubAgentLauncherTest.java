package ai.pijota.agent;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentLauncherTest {

    @Test
    void agentCommandReinvokesMainOnTheJvm() {
        // Under the test JVM (HotSpot, java.home set) the launcher re-invokes ai.pijota.Main via `java`.
        var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));

        assertEquals("ai.pijota.Main", cmd.getLast(), cmd.toString());
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
        // The parent's own classpath is reused so the child loads the same build.
        assertEquals(System.getProperty("java.class.path"), classpath);
    }

    @Test
    void agentCommandIsMutableSoCallerCanAppendFlags() {
        // run() appends --agent/--no-session/--model to the returned list, so it must be mutable.
        var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));
        int before = cmd.size();

        assertDoesNotThrow(() -> cmd.add("--agent"));
        assertEquals(before + 1, cmd.size());
    }

    @Test
    void resolveClasspathFallsBackToCwdTargetClassesWhenNoParentClasspath() {
        // When java.class.path is empty, the launcher falls back to the build output it can find. This project's
        // own target/classes (relative to the working dir) satisfies that fallback during the test run.
        String saved = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", "");
            var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));
            int cpIndex = cmd.indexOf("-cp");
            String classpath = cmd.get(cpIndex + 1);
            assertTrue(classpath.contains("target" + File.separator + "classes") || classpath.endsWith(".jar")
                                            || classpath.equals("."), "unexpected fallback classpath: " + classpath);
        } finally {
            System.setProperty("java.class.path", saved);
        }
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
}
