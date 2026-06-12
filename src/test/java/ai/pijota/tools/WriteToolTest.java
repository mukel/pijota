package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WriteToolTest {
    @TempDir
    Path tempDir;

    @Test
    void createsFileAndReturnsByteAndLineCounts() throws Exception {
        var path = tempDir.resolve("output.txt");

        var result = new WriteTool().execute("tc1", Map.of("path", path.toString(), "content", "hello\nworld"),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertTrue(Files.exists(path));
        assertEquals("hello\nworld", Files.readString(path));
        @SuppressWarnings("unchecked")
        var details = (Map<String, Object>) result.details();
        assertEquals(11, (int) details.get("bytes"));
        assertEquals(2L, (long) details.get("lines"));
    }

    @Test
    void overwritesExistingFile() throws Exception {
        var path = tempDir.resolve("existing.txt");
        Files.writeString(path, "old content");

        new WriteTool().execute("tc1", Map.of("path", path.toString(), "content", "new content"), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        assertEquals("new content", Files.readString(path));
    }

    @Test
    void createsParentDirectories() throws Exception {
        var path = tempDir.resolve("nested/dir/file.txt");

        new WriteTool().execute("tc1", Map.of("path", path.toString(), "content", "data"), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);

        assertEquals("data", Files.readString(path));
    }
}
