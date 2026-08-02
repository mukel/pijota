package org.pijota.tools;

import org.pijota.config.PathDisplay;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ToolLabelsTest {

    @Test
    void bashDetailStripsCdBoilerplateAndQuotes() {
        var detail = ToolLabels.detail("bash", () -> "{\"command\":\"cd /home/user/project && python3 -c 'print(1)'\"}",
                                        "/cwd", PathDisplay.COMPACT);
        assertEquals("python3 -c 'print(1)'", detail,
                                        "cd boilerplate is stripped and the command is not wrapped in quotes");
    }

    @Test
    void bashDetailKeepsCdWhenThePathCarriesOperators() {
        var detail = ToolLabels.detail("bash", () -> "{\"command\":\"cd a; rm -rf b && echo done\"}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(detail.startsWith("cd a; rm"), "an unusual cd survives verbatim: " + detail);
    }

    @Test
    void detailForEachToolTypeSummarizesTheKeyArgument() {
        assertTrue(ToolLabels.detail("read", () -> "{\"filePath\":\"src/Foo.java\"}", "/cwd", PathDisplay.COMPACT)
                                        .contains("Foo.java"));
        assertTrue(ToolLabels.detail("write", () -> "{\"filePath\":\"out.txt\"}", "/cwd", PathDisplay.COMPACT)
                                        .contains("out.txt"));
        assertTrue(ToolLabels.detail("edit", () -> "{\"filePath\":\"a/b.java\"}", "/cwd", PathDisplay.COMPACT)
                                        .contains("b.java"));
        assertTrue(ToolLabels.detail("bash", () -> "{\"command\":\"npm test\"}", "/cwd", PathDisplay.COMPACT)
                                        .contains("npm test"));
        assertTrue(ToolLabels.detail("grep", () -> "{\"pattern\":\"TODO\"}", "/cwd", PathDisplay.COMPACT)
                                        .contains("TODO"));
        assertTrue(ToolLabels.detail("glob", () -> "{\"pattern\":\"**/*.java\"}", "/cwd", PathDisplay.COMPACT)
                                        .contains("*.java"));
        assertTrue(ToolLabels.detail("webfetch", () -> "{\"url\":\"https://example.com\"}", "/cwd", PathDisplay.COMPACT)
                                        .contains("example.com"));
        assertTrue(ToolLabels.detail("websearch", () -> "{\"query\":\"java native image\"}", "/cwd",
                                        PathDisplay.COMPACT).contains("java native"));
        assertNotNull(ToolLabels.detail("ls", () -> "{\"path\":\".\"}", "/cwd", PathDisplay.COMPACT));
        assertNotNull(ToolLabels.detail("unknown", () -> "{}", "/cwd", PathDisplay.COMPACT));
    }

    @Test
    void traceRead() {
        var trace = ToolLabels.trace("read", () -> "{\"filePath\":\"/tmp/file.txt\"}", "/home/user",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("Read"));
        assertTrue(trace.contains("file.txt") || trace.contains("/tmp/file.txt"));
    }

    @Test
    void traceReadWithOffsetLimit() {
        var trace = ToolLabels.trace("read", () -> "{\"filePath\":\"file.txt\",\"offset\":10,\"limit\":20}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("["));
        assertTrue(trace.contains("offset=10"));
        assertTrue(trace.contains("limit=20"));
    }

    @Test
    void traceWrite() {
        var trace = ToolLabels.trace("write", () -> "{\"filePath\":\"/tmp/out.txt\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Write"));
    }

    @Test
    void traceEdit() {
        var trace = ToolLabels.trace("edit", () -> "{\"filePath\":\"src/Foo.java\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Edit"));
    }

    @Test
    void traceBash() {
        var trace = ToolLabels.trace("bash", () -> "{\"command\":\"ls -la\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Bash"));
        assertTrue(trace.contains("ls -la"));
    }

    @Test
    void traceGrep() {
        var trace = ToolLabels.trace("grep", () -> "{\"pattern\":\"TODO\",\"path\":\"src/\"}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("Grep"));
        assertTrue(trace.contains("TODO"));
    }

    @Test
    void traceGlob() {
        var trace = ToolLabels.trace("glob", () -> "{\"pattern\":\"*.java\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Glob"));
        assertTrue(trace.contains("*.java"));
    }

    @Test
    void traceLs() {
        var trace = ToolLabels.trace("ls", () -> "{\"path\":\"src/\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Ls"));
    }

    @Test
    void traceQuestion() {
        var trace = ToolLabels.trace("question", () -> "{\"question\":\"what?\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("Question"));
        assertTrue(trace.contains("what?"));
    }

    @Test
    void traceWebfetch() {
        var trace = ToolLabels.trace("webfetch", () -> "{\"url\":\"https://example.com\"}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("WebFetch"));
        assertTrue(trace.contains("example.com"));
    }

    @Test
    void traceWebsearch() {
        var trace = ToolLabels.trace("websearch", () -> "{\"query\":\"java 25\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("WebSearch"));
        assertTrue(trace.contains("java 25"));
    }

    @Test
    void traceUnknownTool() {
        var trace = ToolLabels.trace("custom_tool", () -> "{\"key\":\"val\"}", "/cwd", PathDisplay.COMPACT);
        assertEquals("→ custom_tool", trace);
    }

    @Test
    void traceNullArgs() {
        var trace = ToolLabels.trace("read", null, "/cwd", PathDisplay.COMPACT);
        assertEquals("→ read", trace);
    }

    @Test
    void traceEmptyArgs() {
        var trace = ToolLabels.trace("read", () -> null, "/cwd", PathDisplay.COMPACT);
        assertEquals("→ read", trace);
    }

    @Test
    void detailRead() {
        var detail = ToolLabels.detail("read", () -> "{\"filePath\":\"file.txt\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(detail.contains("file.txt"));
    }

    @Test
    void detailBash() {
        var detail = ToolLabels.detail("bash", () -> "{\"command\":\"make test\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(detail.contains("make test"));
    }

    @Test
    void detailWrite() {
        var detail = ToolLabels.detail("write", () -> "{\"filePath\":\"out.txt\"}", "/cwd", PathDisplay.COMPACT);
        assertTrue(detail.contains("out.txt"));
    }

    @Test
    void detailUnknownReturnsEmpty() {
        var detail = ToolLabels.detail("unknown", () -> "{\"x\":\"y\"}", "/cwd", PathDisplay.COMPACT);
        assertEquals("", detail);
    }

    @Test
    void pathArgUsesPathThenFilePathThenFilePathLowerCase() {
        assertEquals("a", ToolLabels.pathArg(Map.of("path", "a")));
        assertEquals("b", ToolLabels.pathArg(Map.of("filePath", "b")));
        assertEquals("c", ToolLabels.pathArg(Map.of("file_path", "c")));
        assertEquals("", ToolLabels.pathArg(Map.of()));
    }

    @Test
    void traceTaskChain() {
        var trace = ToolLabels.trace("task", () -> "{\"chain\":[{\"subagent_type\":\"explore\"}]}", "/cwd",
                                        PathDisplay.COMPACT);
        assertTrue(trace.contains("chain"));
        assertTrue(trace.contains("1 steps"));
    }

    @Test
    void traceTaskParallel() {
        var trace = ToolLabels.trace("task", () -> "{\"tasks\":[{\"subagent_type\":\"a\"},{\"subagent_type\":\"b\"}]}",
                                        "/cwd", PathDisplay.COMPACT);
        assertTrue(trace.contains("parallel"));
        assertTrue(trace.contains("2 tasks"));
    }

    @Test
    void displayPathHandlesNull() {
        assertEquals(".", ToolLabels.displayPath(null, "/tmp"));
        assertEquals(".", ToolLabels.displayPath("", "/tmp"));
    }

    @Test
    void displayPathReturnsRelativePath() {
        var result = ToolLabels.displayPath("/tmp/sub/file.txt", "/tmp");
        assertFalse(result.startsWith("/"));
    }

    @Test
    void fileNameExtractsName() {
        assertEquals("file.txt", ToolLabels.fileName("/tmp/sub/file.txt"));
        assertEquals("", ToolLabels.fileName(""));
        assertEquals("", ToolLabels.fileName(null));
    }

    @Test
    void optionsBuildsKeyValueString() {
        assertEquals(" [key1=val1, key2=999]", ToolLabels.options(Map.of("key1", "val1", "key2", 999), "key1", "key2"));
    }

    @Test
    void optionsSkipsNullsAndBlanks() {
        assertEquals("", ToolLabels.options(Map.of("x", ""), "x"));
        assertEquals("", ToolLabels.options(Map.of(), "missing"));
    }

    @Test
    void quoteWrapsInDoubleQuotes() {
        assertEquals("\"hello\"", ToolLabels.quote("hello"));
        assertEquals("\"\"", ToolLabels.quote(null));
    }

    @Test
    void oneLineCollapsesNewlines() {
        assertEquals("a b", ToolLabels.oneLine("a\nb"));
    }

    @Test
    void questionTextExtractsQuestion() {
        assertEquals("what is this?", ToolLabels.questionText(Map.of("question", "what is this?")));
        assertEquals("alt text", ToolLabels.questionText(Map.of("text", "alt text")));
    }

    @Test
    void taskLabelDetectsChain() {
        var result = ToolLabels.taskLabel(Map.of("chain", List.of("step1", "step2")));
        assertTrue(result.contains("chain"));
        assertTrue(result.contains("2 steps"));
    }

    @Test
    void taskLabelDetectsParallel() {
        var result = ToolLabels.taskLabel(Map.of("tasks", List.of("a", "b", "c")));
        assertTrue(result.contains("parallel"));
        assertTrue(result.contains("3 tasks"));
    }

    @Test
    void taskDetailShowsStepCount() {
        assertEquals("2 steps", ToolLabels.taskDetail(Map.of("chain", List.of("a", "b"))));
        assertEquals("3 tasks", ToolLabels.taskDetail(Map.of("tasks", List.of("a", "b", "c"))));
    }

    @Test
    void displayPathCompactsDirsButKeepsFilename() {
        String full = "src/main/java/ai/pijota/util/Platform.java";
        assertEquals(full, ToolLabels.displayPath(full, "/proj", PathDisplay.RELATIVE)); // off → full cwd-relative path
        assertEquals("s/m/j/a/p/u/Platform.java", ToolLabels.displayPath(full, "/proj", PathDisplay.COMPACT)); // on →
                                                                                                               // compact
        assertEquals("Platform.java", ToolLabels.displayPath("Platform.java", "/proj", PathDisplay.COMPACT)); // file in
                                                                                                              // cwd:
                                                                                                              // unchanged
    }

    @Test
    void displayPathCompactKeepsLeadingDotDirs() {
        assertEquals(".c/settings.json", ToolLabels.displayPath(".config/settings.json", "/proj", PathDisplay.COMPACT));
    }

    @Test
    void displayPathRendersAllFourModes() {
        String rel = "src/main/java/ai/pijota/util/Platform.java";
        assertEquals(rel, ToolLabels.displayPath(rel, "/proj", PathDisplay.RELATIVE));
        assertEquals("s/m/j/a/p/u/Platform.java", ToolLabels.displayPath(rel, "/proj", PathDisplay.COMPACT));
        assertEquals("Platform.java", ToolLabels.displayPath(rel, "/proj", PathDisplay.NAME));
        assertEquals("/proj/" + rel, ToolLabels.displayPath(rel, "/proj", PathDisplay.FULL));
    }

    @Test
    void theBashCommandBudgetDiffersBySurface() {
        // The TUI transcript is where you inspect what ran, so the command stays whole there; chat/web hints are
        // notifications, where the same command would drown the message. One label, two deliberate budgets.
        String command = "grep -rn 'needle' src/ | head -50 && echo '"
                                        + "this-tail-says-what-the-command-was-actually-for".repeat(3) + "'";
        Supplier<String> args = () -> "{\"command\":\"" + command + "\"}";

        String compact = ToolLabels.detail("bash", args, "/proj", PathDisplay.COMPACT);
        String full = ToolLabels.detail("bash", args, "/proj", PathDisplay.COMPACT, ToolLabels.FULL_COMMAND);

        assertTrue(compact.length() < command.length(), "chat hints stay short: " + compact.length());
        assertTrue(full.length() > compact.length(), "the transcript shows more than the hint");
        assertTrue(full.contains("actually-for"), "the tail, which says what it was FOR, survives: " + full);
    }
}
