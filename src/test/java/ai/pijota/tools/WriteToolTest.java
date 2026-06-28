package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class WriteToolTest {
    @TempDir
    Path tempDir;

    @Test
    void createsFileAndReturnsByteAndLineCounts() throws Exception {
        var path = tempDir.resolve("output.txt");

        var result = run(new WriteTool(), Map.of("path", path.toString(), "content", "hello\nworld"),
                                        tempDir.toString());

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

        run(new WriteTool(), Map.of("path", path.toString(), "content", "new content"), tempDir.toString());

        assertEquals("new content", Files.readString(path));
    }

    @Test
    void createsParentDirectories() throws Exception {
        var path = tempDir.resolve("nested/dir/file.txt");

        run(new WriteTool(), Map.of("path", path.toString(), "content", "data"), tempDir.toString());

        assertEquals("data", Files.readString(path));
    }

    @Test
    void renderResultErrorReturnsContent() {
        var tool = new WriteTool();
        var result = Tool.ToolResult.error("denied");
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("denied", view.summary());
    }

    @Test
    void renderResultBlankContentReturnsSummaryOnly() {
        var tool = new WriteTool();
        var result = Tool.ToolResult.success("wrote file");
        var view = tool.renderResult(new Tool.Args(Map.of("content", "   ")), result, "/tmp");
        assertEquals("wrote file", view.summary());
        assertTrue(view.body().isEmpty());
    }

    @Test
    void renderResultPreviewTruncatesAfterFiveLines() {
        var tool = new WriteTool();
        var content = IntStream.range(0, 10).mapToObj(i -> "line" + i).collect(Collectors.joining("\n"));
        var result = Tool.ToolResult.success("wrote file");
        var view = tool.renderResult(new Tool.Args(Map.of("content", content)), result, "/tmp");
        assertEquals("wrote file", view.summary());
        assertTrue(view.body().contains("line4"));
        assertFalse(view.body().contains("line5"));
        assertTrue(view.body().contains("… 5 more lines"));
    }
}
