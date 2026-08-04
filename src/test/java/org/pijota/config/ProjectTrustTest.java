package org.pijota.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProjectTrust#hasProtectedResources} is the gate that decides whether a project directory gets a trust prompt
 * before pijota loads code from it. Extensions, skills and prompt templates all execute or steer the model, so a name
 * missing from the protected list means project-local code loads with nothing asked. The gate had no tests.
 */
class ProjectTrustTest {

    /**
     * Everything that can run code or rewrite the system prompt. Each must independently trip the gate. {@code
     * mcp.json} is here because it is the sharpest of them: every entry is a command pijota spawns at startup, and it
     * was missing from the list while being loaded unconditionally, so a repository carrying only that file got no
     * prompt at all.
     */
    private static final List<String> EXECUTABLE = List.of("settings.json", "extensions", "skills", "prompts", "themes",
                                    "agents", "mcp.json", "SYSTEM.md", "APPEND_SYSTEM.md");

    private static void touch(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        // Directories in the list (extensions/, skills/, …) and files both count: the check is existence, not kind.
        if (name.endsWith(".json") || name.endsWith(".md"))
            Files.writeString(dir.resolve(name), "{}");
        else
            Files.createDirectories(dir.resolve(name));
    }

    @Test
    void aBareDirectoryNeedsNoTrustPrompt(@TempDir Path cwd) {
        assertFalse(ProjectTrust.hasProtectedResources(cwd.toString()));
    }

    @Test
    void everyProtectedNameTripsTheGateOnItsOwn(@TempDir Path root) throws IOException {
        for (String name : EXECUTABLE) {
            Path cwd = Files.createDirectories(root.resolve("case-" + name.replace('.', '-')));
            assertFalse(ProjectTrust.hasProtectedResources(cwd.toString()), "clean fixture for " + name);
            touch(cwd.resolve(".pijota"), name);
            assertTrue(ProjectTrust.hasProtectedResources(cwd.toString()),
                                            ".pijota/" + name + " must require trust before it is loaded");
        }
    }

    /**
     * Pijota no longer reads pi's directories, and the gate must not claim otherwise: warning about a folder nothing
     * loads is the same failure as staying silent about one that does, pointed the other way.
     */
    @Test
    void piProjectDirectoriesAreNoLongerRead(@TempDir Path root) throws IOException {
        for (String name : EXECUTABLE) {
            Path cwd = Files.createDirectories(root.resolve("pi-" + name.replace('.', '-')));
            touch(cwd.resolve(".pi"), name);
            assertFalse(ProjectTrust.hasProtectedResources(cwd.toString()), ".pi/" + name + " is not loaded");
        }
    }

    @Test
    void unrelatedContentInTheProjectDirectoryDoesNotTripTheGate(@TempDir Path cwd) throws IOException {
        touch(cwd.resolve(".pijota"), "CONTEXT.md"); // read as context, never executed
        Files.writeString(cwd.resolve("README.md"), "hello");
        Files.createDirectories(cwd.resolve("src"));
        assertFalse(ProjectTrust.hasProtectedResources(cwd.toString()),
                                        "prompting when nothing executable is present trains users to dismiss it");
    }

    @Test
    void aMissingOrBlankDirectoryIsNotProtectedAndDoesNotThrow() {
        assertFalse(ProjectTrust.hasProtectedResources(null));
        assertFalse(ProjectTrust.hasProtectedResources(""));
        assertFalse(ProjectTrust.hasProtectedResources("   "));
        assertFalse(ProjectTrust.hasProtectedResources("/nonexistent/path/" + System.nanoTime()));
    }

}
