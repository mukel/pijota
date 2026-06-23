package ai.pijota.tui;

import ai.pijota.config.PathDisplay;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRenderTest {

    @Test
    void traceRead() {
        var trace = ToolRender.trace("read", () -> "{\"filePath\":\"/tmp/file.txt\"}", "/home/user",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("Read"));
        assertTrue(trace.contains("file.txt") || trace.contains("/tmp/file.txt"));
    }

    @Test
    void traceReadWithOffsetLimit() {
        var trace = ToolRender.trace("read", () -> "{\"filePath\":\"file.txt\",\"offset\":10,\"limit\":20}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("["));
        assertTrue(trace.contains("offset=10"));
        assertTrue(trace.contains("limit=20"));
    }

    @Test
    void traceWrite() {
        var trace = ToolRender.trace("write", () -> "{\"filePath\":\"/tmp/out.txt\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Write"));
    }

    @Test
    void traceEdit() {
        var trace = ToolRender.trace("edit", () -> "{\"filePath\":\"src/Foo.java\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Edit"));
    }

    @Test
    void traceBash() {
        var trace = ToolRender.trace("bash", () -> "{\"command\":\"ls -la\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Bash"));
        assertTrue(trace.contains("ls -la"));
    }

    @Test
    void traceGrep() {
        var trace = ToolRender.trace("grep", () -> "{\"pattern\":\"TODO\",\"path\":\"src/\"}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("Grep"));
        assertTrue(trace.contains("TODO"));
    }

    @Test
    void traceGlob() {
        var trace = ToolRender.trace("glob", () -> "{\"pattern\":\"*.java\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Glob"));
        assertTrue(trace.contains("*.java"));
    }

    @Test
    void traceLs() {
        var trace = ToolRender.trace("ls", () -> "{\"path\":\"src/\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Ls"));
    }

    @Test
    void traceQuestion() {
        var trace = ToolRender.trace("question", () -> "{\"question\":\"what?\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Question"));
        assertTrue(trace.contains("what?"));
    }

    @Test
    void traceWebfetch() {
        var trace = ToolRender.trace("webfetch", () -> "{\"url\":\"https://example.com\"}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("WebFetch"));
        assertTrue(trace.contains("example.com"));
    }

    @Test
    void traceWebsearch() {
        var trace = ToolRender.trace("websearch", () -> "{\"query\":\"java 25\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("WebSearch"));
        assertTrue(trace.contains("java 25"));
    }

    @Test
    void traceUnknownTool() {
        var trace = ToolRender.trace("custom_tool", () -> "{\"key\":\"val\"}", "/cwd", PathDisplay.COMPACT);
        assertEquals("→ custom_tool", trace);
    }

    @Test
    void traceNullArgs() {
        var trace = ToolRender.trace("read", null, "/cwd", PathDisplay.COMPACT);
        assertEquals("→ read", trace);
    }

    @Test
    void traceEmptyArgs() {
        var trace = ToolRender.trace("read", () -> null, "/cwd", PathDisplay.COMPACT);
        assertEquals("→ read", trace);
    }

    @Test
    void detailRead() {
        var detail = ToolRender.detail("read", () -> "{\"filePath\":\"file.txt\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(detail.contains("file.txt"));
    }

    @Test
    void detailBash() {
        var detail = ToolRender.detail("bash", () -> "{\"command\":\"make test\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(detail.contains("make test"));
    }

    @Test
    void detailWrite() {
        var detail = ToolRender.detail("write", () -> "{\"filePath\":\"out.txt\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(detail.contains("out.txt"));
    }

    @Test
    void detailUnknownReturnsEmpty() {
        var detail = ToolRender.detail("unknown", () -> "{\"x\":\"y\"}", "/cwd", PathDisplay.COMPACT);
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
        var trace = ToolRender.trace("task", () -> "{\"chain\":[{\"subagent_type\":\"explore\"}]}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("chain"));
        assertTrue(trace.contains("1 steps"));
    }

    @Test
    void traceTaskParallel() {
        var trace = ToolRender.trace("task", () -> "{\"tasks\":[{\"subagent_type\":\"a\"},{\"subagent_type\":\"b\"}]}",
                                        "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("parallel"));
        assertTrue(trace.contains("2 tasks"));
    }

    @Test
    void displayPathHandlesNull() {
        assertEquals(".", ToolRender.displayPath(null, "/tmp"));
        assertEquals(".", ToolRender.displayPath("", "/tmp"));
    }

    @Test
    void displayPathReturnsRelativePath() {
        var result = ToolRender.displayPath("/tmp/sub/file.txt", "/tmp");
        assertFalse(result.startsWith("/"));
    }

    @Test
    void fileNameExtractsName() {
        assertEquals("file.txt", ToolRender.fileName("/tmp/sub/file.txt"));
        assertEquals("", ToolRender.fileName(""));
        assertEquals("", ToolRender.fileName(null));
    }

    @Test
    void optionsBuildsKeyValueString() {
        assertEquals(" [key1=val1, key2=999]", ToolRender.options(Map.of("key1", "val1", "key2", 999), "key1", "key2"));
    }

    @Test
    void optionsSkipsNullsAndBlanks() {
        assertEquals("", ToolRender.options(Map.of("x", ""), "x"));
        assertEquals("", ToolRender.options(Map.of(), "missing"));
    }

    @Test
    void quoteWrapsInDoubleQuotes() {
        assertEquals("\"hello\"", ToolRender.quote("hello"));
        assertEquals("\"\"", ToolRender.quote(null));
    }

    @Test
    void oneLineCollapsesNewlines() {
        assertEquals("a b", ToolRender.oneLine("a\nb"));
    }

    @Test
    void questionTextExtractsQuestion() {
        assertEquals("what is this?", ToolRender.questionText(Map.of("question", "what is this?")));
        assertEquals("alt text", ToolRender.questionText(Map.of("text", "alt text")));
    }

    @Test
    void taskLabelDetectsChain() {
        var result = ToolRender.taskLabel(Map.of("chain", List.of("step1", "step2")));
        assertTrue(result.contains("chain"));
        assertTrue(result.contains("2 steps"));
    }

    @Test
    void taskLabelDetectsParallel() {
        var result = ToolRender.taskLabel(Map.of("tasks", List.of("a", "b", "c")));
        assertTrue(result.contains("parallel"));
        assertTrue(result.contains("3 tasks"));
    }

    @Test
    void taskDetailShowsStepCount() {
        assertEquals("2 steps", ToolRender.taskDetail(Map.of("chain", List.of("a", "b"))));
        assertEquals("3 tasks", ToolRender.taskDetail(Map.of("tasks", List.of("a", "b", "c"))));
    }

    @Test
    void displayPathCompactsDirsButKeepsFilename() {
        String full = "src/main/java/ai/pijota/util/Platform.java";
        assertEquals(full, ToolRender.displayPath(full, "/proj", PathDisplay.RELATIVE)); // off → full cwd-relative path
        assertEquals("s/m/j/a/p/u/Platform.java", ToolRender.displayPath(full, "/proj", PathDisplay.COMPACT)); // on →
                                                                                                               // compact
        assertEquals("Platform.java", ToolRender.displayPath("Platform.java", "/proj", PathDisplay.COMPACT)); // file in
                                                                                                              // cwd:
                                                                                                              // unchanged
    }

    @Test
    void displayPathCompactKeepsLeadingDotDirs() {
        assertEquals(".c/settings.json", ToolRender.displayPath(".config/settings.json", "/proj", PathDisplay.COMPACT));
    }

    @Test
    void displayPathRendersAllFourModes() {
        String rel = "src/main/java/ai/pijota/util/Platform.java";
        assertEquals(rel, ToolRender.displayPath(rel, "/proj", PathDisplay.RELATIVE));
        assertEquals("s/m/j/a/p/u/Platform.java", ToolRender.displayPath(rel, "/proj", PathDisplay.COMPACT));
        assertEquals("Platform.java", ToolRender.displayPath(rel, "/proj", PathDisplay.NAME));
        assertEquals("/proj/" + rel, ToolRender.displayPath(rel, "/proj", PathDisplay.FULL));
    }
}
