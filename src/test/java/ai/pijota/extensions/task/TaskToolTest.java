package ai.pijota.extensions.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolTest {

    @Test
    void formatToolCallRead() {
        var result = TaskTool.formatToolCall("read", "{\"filePath\":\"file.txt\"}");
        assertTrue(result.contains("Read") || result.contains("read"), "should indicate read: " + result);
        assertTrue(result.contains("file.txt"), "should include path: " + result);
    }

    @Test
    void formatToolCallWrite() {
        var result = TaskTool.formatToolCall("write", "{\"filePath\":\"out.txt\"}");
        assertTrue(result.contains("Write") || result.contains("write"), "should indicate write: " + result);
        assertTrue(result.contains("out.txt"), "should include path: " + result);
    }

    @Test
    void formatToolCallEdit() {
        var result = TaskTool.formatToolCall("edit", "{\"filePath\":\"src/Foo.java\"}");
        assertTrue(result.contains("Edit") || result.contains("edit"), "should indicate edit: " + result);
        assertTrue(result.contains("Foo.java"), "should include path: " + result);
    }

    @Test
    void formatToolCallBash() {
        var result = TaskTool.formatToolCall("bash", "{\"command\":\"ls -la\"}");
        assertTrue(result.contains("ls -la"), "should contain command: " + result);
    }

    @Test
    void formatToolCallGrep() {
        var result = TaskTool.formatToolCall("grep", "{\"pattern\":\"TODO\",\"filePath\":\"src/\"}");
        assertTrue(result.contains("Grep") || result.contains("grep"), "should indicate grep: " + result);
        assertTrue(result.contains("TODO"), "should include pattern: " + result);
    }

    @Test
    void formatToolCallGlob() {
        var result = TaskTool.formatToolCall("glob", "{\"pattern\":\"*.java\"}");
        assertTrue(result.contains("Glob") || result.contains("glob"), "should indicate glob: " + result);
    }

    @Test
    void formatToolCallLs() {
        var result = TaskTool.formatToolCall("ls", "{\"path\":\"src/\"}");
        assertTrue(result.contains("Ls") || result.contains("List"), "should indicate ls: " + result);
    }

    @Test
    void formatToolCallUnknown() {
        var result = TaskTool.formatToolCall("custom", "{\"x\":\"y\"}");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void formatToolCallInvalidJson() {
        var result = TaskTool.formatToolCall("read", "not json");
        assertTrue(result.contains("read"));
    }
}
