package org.pijota.tools;

import org.pijota.util.Executables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pijota.testkit.ToolRunner.run;

class GlobToolTest {
    @TempDir
    Path tempDir;

    @Test
    void fdBackendListsMatchingFilesDirectly() throws Exception {
        assumeTrue(Executables.isAvailable("fd"), "fd not installed");
        Files.createDirectories(tempDir.resolve("src"));
        Files.createFile(tempDir.resolve("src/App.java"));
        Files.createFile(tempDir.resolve("notes.txt"));

        var result = new GlobTool().fdSearch("*.java", tempDir, 100, 5); // the fd path is otherwise shadowed by rg

        assertEquals("fd", result.backend());
        assertTrue(result.lines().stream().anyMatch(l -> l.contains("App.java")), result.lines().toString());
        assertFalse(result.lines().stream().anyMatch(l -> l.contains("notes.txt")), result.lines().toString());
    }

    @Test
    void findsFilesByPatternWithInjectedBackend() throws Exception {
        Files.createFile(tempDir.resolve("hello.java"));
        Files.createFile(tempDir.resolve("world.kt"));

        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = run(tool, Map.of("pattern", "*.java", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError(), "unexpected error: " + result.content());
        assertTrue(result.content().contains("hello.java"), "result: " + result.content());
        assertFalse(result.content().contains("world.kt"), "result: " + result.content());
    }

    @Test
    void noMatchesReturnsEmptyResult() throws Exception {
        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = run(tool, Map.of("pattern", "*.cpp", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("No files found"));
    }

    @Test
    void missingPatternReportsError() {
        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = run(tool, Map.of("path", tempDir.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("No pattern"));
    }

    @Test
    void nameDescriptionAndDefaultEnabled() {
        var tool = new GlobTool();
        assertEquals("glob", tool.name());
        assertFalse(tool.description().isBlank());
        assertTrue(tool.defaultEnabled());
    }

    @Test
    void reportsPathNotFound() {
        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = run(tool, Map.of("pattern", "*.java", "path", tempDir.resolve("missing").toString()),
                                        tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("Path not found"));
    }

    @Test
    void reportsLimitReached() throws Exception {
        Files.createFile(tempDir.resolve("a.java"));
        Files.createFile(tempDir.resolve("b.java"));

        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = run(tool, Map.of("pattern", "*.java", "path", tempDir.toString(), "limit", 1), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("results limit reached"));
        assertEquals(1, result.details().get("resultLimitReached"));
    }

    @Test
    void javaSearchHandlesDoubleGlobRecursively() throws Exception {
        Files.createDirectories(tempDir.resolve("sub"));
        Files.createFile(tempDir.resolve("sub/c.java"));

        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = run(tool, Map.of("pattern", "**/*.java", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("sub/c.java"));
    }

    @Test
    void realBackendFindsFilesEndToEnd() throws Exception {
        // No injected backend: exercises the production chain (rg → fd → java fallback) + relativize, whichever tool is
        // present. The matched set is identical across backends, so the assertions are deterministic.
        Files.createDirectories(tempDir.resolve("src"));
        Files.createFile(tempDir.resolve("src/App.java"));
        Files.createFile(tempDir.resolve("notes.txt"));

        var result = run(new GlobTool(), Map.of("pattern", "**/*.java", "path", tempDir.toString()),
                                        tempDir.toString());

        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains("App.java"), result.content());
        assertFalse(result.content().contains("notes.txt"), result.content());
        assertNotNull(result.details().get("backend"), "records which backend served the search");
    }

    @Test
    void nonRecursivePatternStaysAtTheTopLevel() throws Exception {
        Files.createDirectories(tempDir.resolve("sub"));
        Files.createFile(tempDir.resolve("top.java"));
        Files.createFile(tempDir.resolve("sub/nested.java"));

        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));
        var result = run(tool, Map.of("pattern", "*.java", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("top.java"), result.content());
        assertFalse(result.content().contains("nested.java"),
                                        "a non-** pattern matches depth 1 only: " + result.content());
    }

    @Test
    void defaultsToTheCwdWhenNoPathGiven() throws Exception {
        Files.createFile(tempDir.resolve("root.java"));

        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));
        var result = run(tool, Map.of("pattern", "*.java"), tempDir.toString()); // no "path" → search cwd

        assertFalse(result.isError());
        assertTrue(result.content().contains("root.java"), result.content());
    }

    @Test
    void abortIsSafeWhenNothingIsRunning() {
        assertDoesNotThrow(new GlobTool()::abort);
    }
}
