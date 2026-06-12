package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GlobToolTest {
    @TempDir
    Path tempDir;

    @Test
    void findsFilesByPatternWithInjectedBackend() throws Exception {
        Files.createFile(tempDir.resolve("hello.java"));
        Files.createFile(tempDir.resolve("world.kt"));

        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = tool.execute("tc1", Map.of("pattern", "*.java", "path", tempDir.toString()), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        assertFalse(result.isError(), "unexpected error: " + result.content());
        assertTrue(result.content().contains("hello.java"), "result: " + result.content());
        assertFalse(result.content().contains("world.kt"), "result: " + result.content());
    }

    @Test
    void noMatchesReturnsEmptyResult() throws Exception {
        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = tool.execute("tc1", Map.of("pattern", "*.cpp", "path", tempDir.toString()), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertTrue(result.content().contains("No files found"));
    }

    @Test
    void missingPatternReportsError() throws Exception {
        var tool = new GlobTool((pattern, searchPath, limit, timeout) -> GlobTool.javaSearch(pattern, searchPath,
                                        limit));

        var result = tool.execute("tc1", Map.of("path", tempDir.toString()), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("No pattern"));
    }
}
