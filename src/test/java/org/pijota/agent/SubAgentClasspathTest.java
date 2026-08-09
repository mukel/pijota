package org.pijota.agent;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentClasspathTest {

    /**
     * A sub-agent starts in a pooled git worktree: a checkout with sources and no {@code target/}. A relative classpath
     * entry is relative to the JVM that owns it, so handing the child {@code target/classes} resolved to nothing there
     * and it died before main -- "Could not find or load main class org.pijota.Main", surfaced as a task that ran zero
     * turns and zero tools. Only the dev launch is relative, which is why it survived: a fat jar and the native image
     * are absolute already.
     */
    @Test
    void everyClasspathEntryHandedToAChildIsAbsolute() {
        String mixed = "target/classes" + File.pathSeparator + "/abs/lib/x.jar" + File.pathSeparator + "libs/y.jar";

        String out = SubAgentLauncher.absolutize(mixed);

        for (String entry : out.split(File.pathSeparator))
            assertTrue(Path.of(entry).isAbsolute(), "still relative, so it means nothing in a worktree: " + entry);
        assertTrue(out.contains("/abs/lib/x.jar"), "an entry that was already absolute is left alone: " + out);
        assertEquals(3, out.split(File.pathSeparator).length, "no entry gained or lost: " + out);
    }

    /** Blank entries would become the working directory itself, quietly putting the worktree on the classpath. */
    @Test
    void blankEntriesAreDroppedRatherThanBecomingTheWorkingDirectory() {
        String out = SubAgentLauncher.absolutize("a.jar" + File.pathSeparator + File.pathSeparator + "b.jar");

        assertEquals(2, out.split(File.pathSeparator).length, out);
        assertFalse(out.contains(File.pathSeparator + File.pathSeparator), out);
    }

    /** The real command a task would launch: whatever the parent's classpath looks like, the child's is absolute. */
    @Test
    void theLaunchCommandCarriesAnAbsoluteClasspath() {
        var cmd = SubAgentLauncher.agentCommand(System.getProperty("user.dir"));

        int cp = cmd.indexOf("-cp");
        if (cp < 0)
            return; // a jar launch uses -jar; nothing relative to fix
        for (String entry : cmd.get(cp + 1).split(File.pathSeparator))
            assertTrue(Path.of(entry).isAbsolute(), "relative entry in a real launch command: " + entry);
    }
}
