package ai.pijota.extensions.task;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolTest {

    @Test
    void formatToolCallRead() {
        assertEquals("→ Read file.txt", TaskTool.formatToolCall("read", "{\"filePath\":\"file.txt\"}"));
    }

    @Test
    void formatToolCallWrite() {
        assertEquals("→ Write out.txt", TaskTool.formatToolCall("write", "{\"filePath\":\"out.txt\"}"));
    }

    @Test
    void formatToolCallEdit() {
        assertEquals("→ Edit Foo.java", TaskTool.formatToolCall("edit", "{\"filePath\":\"src/Foo.java\"}"));
    }

    @Test
    void formatToolCallBash() {
        var result = TaskTool.formatToolCall("bash", "{\"command\":\"ls -la\"}");
        assertTrue(result.contains("ls -la"));
    }

    @Test
    void formatToolCallGrep() {
        var result = TaskTool.formatToolCall("grep", "{\"pattern\":\"TODO\",\"filePath\":\"src/\"}");
        assertTrue(result.contains("Grep"));
        assertTrue(result.contains("TODO"));
    }

    @Test
    void formatToolCallGlob() {
        var result = TaskTool.formatToolCall("glob", "{\"pattern\":\"*.java\"}");
        assertTrue(result.contains("Glob"));
    }

    @Test
    void formatToolCallLs() {
        var result = TaskTool.formatToolCall("ls", "{\"path\":\"src/\"}");
        assertTrue(result.contains("Ls"));
    }

    @Test
    void formatToolCallUnknown() {
        assertEquals("→ custom", TaskTool.formatToolCall("custom", "{\"x\":\"y\"}"));
    }

    @Test
    void formatToolCallInvalidJson() {
        assertEquals("read", TaskTool.formatToolCall("read", "not json"));
    }

    @Test
    void fileExtractsFileName() {
        assertEquals("file.txt", TaskTool.file(Map.of("filePath", "/path/to/file.txt")));
    }

    @Test
    void fileExtractsPath() {
        assertEquals("file.txt", TaskTool.file(Map.of("path", "/a/b/file.txt")));
    }

    @Test
    void fileFallback() {
        assertEquals(".", TaskTool.file(Map.of()));
        assertEquals("justname", TaskTool.file(Map.of("filePath", "justname")));
    }

    @Test
    void shortenShortText() {
        assertEquals("hi", TaskTool.shorten("hi", 10));
    }

    @Test
    void shortenLongText() {
        var result = TaskTool.shorten("hello world this is long", 10);
        assertTrue(result.endsWith("…"));
        assertTrue(result.length() <= 10);
    }

    @Test
    void shortenNull() {
        assertEquals("", TaskTool.shorten(null, 10));
    }

    @Test
    void shortenBlank() {
        assertEquals("", TaskTool.shorten("  ", 10));
    }

    @Test
    void sanitizeReplacesSpecialChars() {
        assertEquals("my_agent_name_", TaskTool.sanitize("my agent name!"));
    }

    @Test
    void sanitizePreservesAlpha() {
        assertEquals("agent-1_v2", TaskTool.sanitize("agent-1_v2"));
    }

    @Test
    void detailsForResultsSingle() {
        var results = List.of(new TaskTool.SubAgentResult("explore", "output", 0, 1, 3, "/tmp/log.log", null));
        var details = TaskTool.detailsForResults("single", results);
        assertEquals("single", details.get("mode"));
        assertEquals(1, details.get("resultCount"));
        assertEquals(1L, details.get("succeeded"));
    }

    @Test
    void detailsForResultsParallel() {
        var r1 = new TaskTool.SubAgentResult("a", "", 0, 1, 0, "/tmp/a.log", null);
        var r2 = new TaskTool.SubAgentResult("b", "", 1, 1, 0, "/tmp/b.log", null);
        var details = TaskTool.detailsForResults("parallel", List.of(r1, r2));
        assertEquals("parallel", details.get("mode"));
        assertEquals(2, details.get("resultCount"));
        assertEquals(1L, details.get("succeeded"));
    }

    @Test
    void subAgentResultEquality() {
        var a = new TaskTool.SubAgentResult("x", "out", 0, 1, 2, "log", "err");
        var b = new TaskTool.SubAgentResult("x", "out", 0, 1, 2, "log", "err");
        assertEquals(a, b);
    }
}
