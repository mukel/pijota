package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EditToolTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesExactSingleEdit() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "hello world\n");

        var result = new EditTool().execute("tc1", Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "hello", "newText", "hi"))), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertEquals("hi world\n", Files.readString(path));
    }

    @Test
    void rejectsAmbiguousOldText() throws Exception {
        var path = tempDir.resolve("dup.txt");
        Files.writeString(path, "abc abc abc");

        var result = new EditTool().execute("tc1", Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "abc ", "newText", "xyz "))), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("unique") || result.content().contains("matches"),
                                        "should reject text that appears multiple times: " + result.content());
    }

    @Test
    void rejectsEmptyOldText() throws Exception {
        var path = tempDir.resolve("e.txt");
        Files.writeString(path, "text");

        var result = new EditTool().execute("tc1", Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "", "newText", "replacement"))), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        assertTrue(result.isError());
    }

}
