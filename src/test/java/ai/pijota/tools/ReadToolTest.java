package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReadToolTest {
    @TempDir Path tempDir;

    @Test
    void readsExistingFileWithLineNumbers() throws Exception {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");

        var result = new ReadTool().execute("tc1", Map.of("path", file.toString()), tempDir.toString())
            .get(1, TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertTrue(result.content().contains("line1"));
        assertTrue(result.content().contains("line2"));
        assertTrue(result.content().contains("line3"));
    }

    @Test
    void respectsOffsetAndLimit() throws Exception {
        var file = tempDir.resolve("nums.txt");
        var sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) sb.append("line").append(i).append('\n');
        Files.writeString(file, sb.toString());

        var result = new ReadTool().execute("tc1",
            Map.of("path", file.toString(), "offset", 5, "limit", 3), tempDir.toString())
            .get(1, TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertTrue(result.content().contains("line5"));
        assertTrue(result.content().contains("line7"));
    }

    @Test
    void reportsMissingFileAsError() throws Exception {
        var result = new ReadTool().execute("tc1",
            Map.of("path", tempDir.resolve("nonexistent").toString()), tempDir.toString())
            .get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void reportsDirectoryAsError() throws Exception {
        var result = new ReadTool().execute("tc1",
            Map.of("path", tempDir.toString()), tempDir.toString())
            .get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("directory"));
    }
}
