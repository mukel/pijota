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

    /** Everything that can run code or rewrite the system prompt. Each must independently trip the gate. */
    private static final List<String> EXECUTABLE = List.of("settings.json", "extensions", "skills", "prompts", "themes",
                                    "SYSTEM.md", "APPEND_SYSTEM.md");

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
     * Pijota reads pi's project directory too, so {@code .pi/} is the same attack surface and must gate identically. A
     * check that covered only {@code .pijota/} would load a pi project's extensions without asking.
     */
    @Test
    void piProjectDirectoriesGateTheSameWay(@TempDir Path root) throws IOException {
        for (String name : EXECUTABLE) {
            Path cwd = Files.createDirectories(root.resolve("pi-" + name.replace('.', '-')));
            touch(cwd.resolve(".pi"), name);
            assertTrue(ProjectTrust.hasProtectedResources(cwd.toString()),
                                            ".pi/" + name + " must require trust before it is loaded");
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
