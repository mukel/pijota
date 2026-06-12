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

    @Test
    void renderResultErrorReturnsContent() {
        var tool = new WriteTool();
        var result = Tool.ToolResult.error("denied");
        var view = tool.renderResult(Map.of(), result, "/tmp");
        assertEquals("denied", view.summary());
    }

    @Test
    void renderResultBlankContentReturnsSummaryOnly() {
        var tool = new WriteTool();
        var result = Tool.ToolResult.success("wrote file");
        var view = tool.renderResult(Map.of("content", "   "), result, "/tmp");
        assertEquals("wrote file", view.summary());
        assertTrue(view.body().isEmpty());
    }

    @Test
    void renderResultPreviewTruncatesAfterFiveLines() {
        var tool = new WriteTool();
        var content = java.util.stream.IntStream.range(0, 10).mapToObj(i -> "line" + i)
                                        .collect(java.util.stream.Collectors.joining("\n"));
        var result = Tool.ToolResult.success("wrote file");
        var view = tool.renderResult(Map.of("content", content), result, "/tmp");
        assertEquals("wrote file", view.summary());
        assertTrue(view.body().contains("line4"));
        assertFalse(view.body().contains("line5"));
        assertTrue(view.body().contains("… 5 more lines"));
    }
}
