package org.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class LsToolTest {
    @TempDir
    Path tempDir;

    @Test
    void listsDirectoryContents() throws Exception {
        Files.createFile(tempDir.resolve("a.txt"));
        Files.createFile(tempDir.resolve("b.txt"));
        Files.createDirectory(tempDir.resolve("sub"));

        var result = run(new LsTool(), Map.of("path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("a.txt"));
        assertTrue(result.content().contains("b.txt"));
        assertTrue(result.content().contains("sub/"));
    }

    @Test
    void reportsNonDirectoryAsError() throws Exception {
        var file = tempDir.resolve("file.txt");
        Files.writeString(file, "data");

        var result = run(new LsTool(), Map.of("path", file.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("Not a directory"));
    }

    @Test
    void emptyDirectoryReportsEmpty() throws Exception {
        var result = run(new LsTool(), Map.of("path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("empty"));
    }
}
