package ai.pijota.tui;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRenderTest {

    @Test
    void traceRead() {
        var trace = ToolRender.trace("read", () -> "{\"filePath\":\"/tmp/file.txt\"}", "/home/user", true);
        assertTrue(trace.contains("Read"));
        assertTrue(trace.contains("file.txt") || trace.contains("/tmp/file.txt"));
    }

    @Test
    void traceReadWithOffsetLimit() {
        var trace = ToolRender.trace("read", () -> "{\"filePath\":\"file.txt\",\"offset\":10,\"limit\":20}", "/cwd",
                                        true);
        assertTrue(trace.contains("["));
        assertTrue(trace.contains("offset=10"));
        assertTrue(trace.contains("limit=20"));
    }

    @Test
    void traceWrite() {
        var trace = ToolRender.trace("write", () -> "{\"filePath\":\"/tmp/out.txt\"}", "/cwd", true);
        assertTrue(trace.contains("Write"));
    }

    @Test
    void traceEdit() {
        var trace = ToolRender.trace("edit", () -> "{\"filePath\":\"src/Foo.java\"}", "/cwd", true);
        assertTrue(trace.contains("Edit"));
    }

    @Test
    void traceBash() {
        var trace = ToolRender.trace("bash", () -> "{\"command\":\"ls -la\"}", "/cwd", true);
        assertTrue(trace.contains("Bash"));
        assertTrue(trace.contains("ls -la"));
    }

    @Test
    void traceGrep() {
        var trace = ToolRender.trace("grep", () -> "{\"pattern\":\"TODO\",\"path\":\"src/\"}", "/cwd", true);
        assertTrue(trace.contains("Grep"));
        assertTrue(trace.contains("TODO"));
    }

    @Test
    void traceGlob() {
        var trace = ToolRender.trace("glob", () -> "{\"pattern\":\"*.java\"}", "/cwd", true);
        assertTrue(trace.contains("Glob"));
        assertTrue(trace.contains("*.java"));
    }

    @Test
    void traceLs() {
        var trace = ToolRender.trace("ls", () -> "{\"path\":\"src/\"}", "/cwd", true);
        assertTrue(trace.contains("Ls"));
    }

    @Test
    void traceQuestion() {
        var trace = ToolRender.trace("question", () -> "{\"question\":\"what?\"}", "/cwd", true);
        assertTrue(trace.contains("Question"));
        assertTrue(trace.contains("what?"));
    }

    @Test
    void traceWebfetch() {
        var trace = ToolRender.trace("webfetch", () -> "{\"url\":\"https://example.com\"}", "/cwd", true);
        assertTrue(trace.contains("WebFetch"));
        assertTrue(trace.contains("example.com"));
    }

    @Test
    void traceWebsearch() {
        var trace = ToolRender.trace("websearch", () -> "{\"query\":\"java 25\"}", "/cwd", true);
        assertTrue(trace.contains("WebSearch"));
        assertTrue(trace.contains("java 25"));
    }

    @Test
    void traceTodowrite() {
        var trace = ToolRender.trace("todowrite",
                                        () -> "{\"todos\":[{\"content\":\"x\",\"status\":\"pending\",\"priority\":\"high\"}]}",
                                        "/cwd", true);
        assertTrue(trace.contains("TodoWrite"));
        assertTrue(trace.contains("1 items"));
    }

    @Test
    void traceUnknownTool() {
        var trace = ToolRender.trace("custom_tool", () -> "{\"key\":\"val\"}", "/cwd", true);
        assertEquals("→ custom_tool", trace);
    }

    @Test
    void traceNullArgs() {
        var trace = ToolRender.trace("read", null, "/cwd", true);
        assertEquals("→ read", trace);
    }

    @Test
    void traceEmptyArgs() {
        var trace = ToolRender.trace("read", () -> null, "/cwd", true);
        assertEquals("→ read", trace);
    }

    @Test
    void detailRead() {
        var detail = ToolRender.detail("read", () -> "{\"filePath\":\"file.txt\"}", "/cwd", true);
        assertTrue(detail.contains("file.txt"));
    }

    @Test
    void detailBash() {
        var detail = ToolRender.detail("bash", () -> "{\"command\":\"make test\"}", "/cwd", true);
        assertTrue(detail.contains("make test"));
    }

    @Test
    void detailWrite() {
        var detail = ToolRender.detail("write", () -> "{\"filePath\":\"out.txt\"}", "/cwd", true);
        assertTrue(detail.contains("out.txt"));
    }

    @Test
    void detailUnknownReturnsEmpty() {
        var detail = ToolRender.detail("unknown", () -> "{\"x\":\"y\"}", "/cwd", true);
        assertEquals("", detail);
    }

    @Test
    void pathArgUsesPathThenFilePathThenFilePathLowerCase() {
        assertEquals("a", ToolRender.pathArg(Map.of("path", "a")));
        assertEquals("b", ToolRender.pathArg(Map.of("filePath", "b")));
        assertEquals("c", ToolRender.pathArg(Map.of("file_path", "c")));
        assertEquals("", ToolRender.pathArg(Map.of()));
    }

    @Test
    void traceTaskChain() {
        var trace = ToolRender.trace("task", () -> "{\"chain\":[{\"subagent_type\":\"explore\"}]}", "/cwd", true);
        assertTrue(trace.contains("chain"));
        assertTrue(trace.contains("1 steps"));
    }

    @Test
    void traceTaskParallel() {
        var trace = ToolRender.trace("task", () -> "{\"tasks\":[{\"subagent_type\":\"a\"},{\"subagent_type\":\"b\"}]}",
                                        "/cwd", true);
        assertTrue(trace.contains("parallel"));
        assertTrue(trace.contains("2 tasks"));
    }
}
