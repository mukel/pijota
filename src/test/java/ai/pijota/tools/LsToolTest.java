package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LsToolTest {
    @TempDir
    Path tempDir;

    @Test
    void listsDirectoryContents() throws Exception {
        Files.createFile(tempDir.resolve("a.txt"));
        Files.createFile(tempDir.resolve("b.txt"));
        Files.createDirectory(tempDir.resolve("sub"));

        var result = new LsTool().execute("tc1", Map.of("path", tempDir.toString()), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertTrue(result.content().contains("a.txt"));
        assertTrue(result.content().contains("b.txt"));
        assertTrue(result.content().contains("sub/"));
    }

    @Test
    void reportsNonDirectoryAsError() throws Exception {
        var file = tempDir.resolve("file.txt");
        Files.writeString(file, "data");

        var result = new LsTool().execute("tc1", Map.of("path", file.toString()), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("Not a directory"));
    }

    @Test
    void emptyDirectoryReportsEmpty() throws Exception {
        var result = new LsTool().execute("tc1", Map.of("path", tempDir.toString()), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertTrue(result.content().contains("empty"));
    }
}
