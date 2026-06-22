package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class ReadToolTest {
    @TempDir
    Path tempDir;

    @Test
    void readsExistingFileWithLineNumbers() throws Exception {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");

        var result = run(new ReadTool(), Map.of("path", file.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("line1"));
        assertTrue(result.content().contains("line2"));
        assertTrue(result.content().contains("line3"));
    }

    @Test
    void respectsOffsetAndLimit() throws Exception {
        var file = tempDir.resolve("nums.txt");
        var sb = new StringBuilder();
        for (int i = 1; i <= 20; i++)
            sb.append("line").append(i).append('\n');
        Files.writeString(file, sb.toString());

        var result = run(new ReadTool(), Map.of("path", file.toString(), "offset", 5, "limit", 3), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("line5"));
        assertTrue(result.content().contains("line7"));
    }

    @Test
    void reportsMissingFileAsError() throws Exception {
        var result = run(new ReadTool(), Map.of("path", tempDir.resolve("nonexistent").toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void reportsDirectoryAsError() throws Exception {
        var result = run(new ReadTool(), Map.of("path", tempDir.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("directory"));
    }

    @Test
    void rejectsMissingPath() {
        var result = run(new ReadTool(), Map.of(), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("No path"));
    }

    @Test
    void reportsOffsetBeyondEnd() throws Exception {
        var file = tempDir.resolve("short.txt");
        Files.writeString(file, "one\ntwo");

        var result = run(new ReadTool(), Map.of("path", file.toString(), "offset", 10), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("beyond end"));
    }

    @Test
    void emptyFileReturnsZeroTotalLines() throws Exception {
        var file = Files.createFile(tempDir.resolve("empty.txt"));

        var result = run(new ReadTool(), Map.of("path", file.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertEquals("", result.content());
        assertEquals(0, result.details().get("totalLines"));
    }

    @Test
    void limitZeroShowsContinuationMessage() throws Exception {
        var file = tempDir.resolve("lines.txt");
        Files.writeString(file, "a\nb\nc");

        var result = run(new ReadTool(), Map.of("path", file.toString(), "limit", 0), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("more lines in file"));
        assertTrue((Boolean) result.details().get("truncated"));
        assertEquals(1, result.details().get("nextOffset"));
    }

    @Test
    void truncatesLongLines() throws Exception {
        var file = tempDir.resolve("longline.txt");
        var line = "x".repeat(2500);
        Files.writeString(file, line);

        var result = run(new ReadTool(), Map.of("path", file.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("(line truncated to 2000 chars)"));
    }

    @Test
    void truncatesByByteLimit() throws Exception {
        var file = tempDir.resolve("bytelimit.txt");
        var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < 40; i++)
            lines.add("x".repeat(2000));
        Files.write(file, lines);

        var result = run(new ReadTool(), Map.of("path", file.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue((Boolean) result.details().get("truncated"));
        assertEquals("bytes", result.details().get("truncatedBy"));
    }

    @Test
    void renderResultErrorReturnsContent() {
        var tool = new ReadTool();
        var result = Tool.ToolResult.error("fail");
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("fail", view.summary());
    }

    @Test
    void renderResultShowsOffsetAndLimit() {
        var tool = new ReadTool();
        var result = Tool.ToolResult.success("", Map.of("totalLines", 100, "offset", 5, "limit", 10));
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("100 line(s) [offset=5] [limit=10]", view.summary());
    }

    @Test
    void renderResultHidesDefaultLimit() {
        var tool = new ReadTool();
        var result = Tool.ToolResult.success("", Map.of("totalLines", 5, "offset", 1, "limit", 2000));
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("5 line(s)", view.summary());
    }
}
