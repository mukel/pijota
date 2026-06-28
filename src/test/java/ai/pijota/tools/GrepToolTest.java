package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GrepToolTest {
    @TempDir
    Path tempDir;

    private static boolean ripgrepAvailable() {
        try {
            return new ProcessBuilder("rg", "--version").redirectError(ProcessBuilder.Redirect.DISCARD)
                                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void realRipgrepSearchFindsMatchingLines() throws Exception {
        assumeTrue(ripgrepAvailable(), "ripgrep not installed");
        Files.writeString(tempDir.resolve("a.txt"), "hello world\nfoo bar\n");
        Files.writeString(tempDir.resolve("b.txt"), "nothing relevant here\n");

        var result = run(new GrepTool(), Map.of("pattern", "hello", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains("hello world"), result.content());
        assertFalse(result.content().contains("nothing relevant"), result.content());
    }

    @Test
    void parseMatchParsesRipgrepJsonOutput() {
        var json = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"src/Main.java\"},"
                                        + "\"line_number\":42,\"lines\":{\"text\":\"hello world\\n\"}}}";

        var match = GrepTool.parseMatch(json);

        assertNotNull(match);
        assertEquals(42, match.line());
        assertEquals("hello world", match.text());
        assertEquals(Path.of("src/Main.java"), match.path());
    }

    @Test
    void parseMatchReturnsNullForNonMatchTypes() {
        assertNull(GrepTool.parseMatch("{\"type\":\"begin\"}"));
        assertNull(GrepTool.parseMatch("not json"));
        assertNull(GrepTool.parseMatch(""));
    }

    @Test
    void parseMatchReturnsNullWhenPathMissing() {
        var json = "{\"type\":\"match\",\"data\":{\"line_number\":5,\"lines\":{\"text\":\"x\\n\"}}}";
        assertNull(GrepTool.parseMatch(json));
    }

    @Test
    void parseMatchReturnsNullWhenLineInvalid() {
        var json = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"a.txt\"},\"line_number\":-1,"
                                        + "\"lines\":{\"text\":\"x\\n\"}}}";
        assertNull(GrepTool.parseMatch(json));
    }

    @Test
    void truncateLineLeavesShortLinesIntact() {
        var t = GrepTool.truncateLine("short");
        assertFalse(t.truncated());
        assertEquals("short", t.text());
    }

    @Test
    void truncateLineTruncatesLongLine() {
        var longLine = "a".repeat(600);
        var t = GrepTool.truncateLine(longLine);
        assertTrue(t.truncated());
        assertTrue(t.text().endsWith("…"));
        assertEquals(500, t.text().length());
    }

    @Test
    void renderResultReturnsContentOnError() {
        var tool = new GrepTool();
        var result = Tool.ToolResult.error("boom");
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("boom", view.summary());
    }

    @Test
    void renderResultShowsMatchCount() {
        var tool = new GrepTool();
        var result = Tool.ToolResult.success("x", Map.of("matchCount", 7));
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("7 match(es)", view.summary());
    }

    @Test
    void renderResultFallsBackToContent() {
        var tool = new GrepTool();
        var result = Tool.ToolResult.success("output", Map.of());
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("output", view.summary());
    }

    @Test
    void reportsNoPatternAsError() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 0, false));

        var result = run(tool, Map.of("path", tempDir.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("No pattern"));
    }

    @Test
    void missingRipgrepReturnsAnActionableInstallMessage() {
        // backend throws the JVM "binary not on PATH" IOException, as the real ripgrep spawn would
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> {
            throw new java.io.IOException("Cannot run program \"rg\": error=2, No such file or directory");
        });

        var result = run(tool, Map.of("pattern", "x"), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("ripgrep") && result.content().toLowerCase().contains("not found"),
                                        result.content());
        // the confusing raw JVM string is not leaked to the user
        assertFalse(result.content().contains("error=2"), result.content());
    }

    @Test
    void reportsBlankPatternAsError() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 0, false));

        var result = run(tool, Map.of("pattern", "   ", "path", tempDir.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("No pattern"));
    }

    @Test
    void reportsPathNotFound() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 0, false));
        var missing = tempDir.resolve("does-not-exist");

        var result = run(tool, Map.of("pattern", "x", "path", missing.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("Path not found"));
    }

    @Test
    void reportsRipgrepErrorExitCodeWithStderr() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "bad arg", 2, false));

        var result = run(tool, Map.of("pattern", "x", "path", tempDir.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("bad arg"));
    }

    @Test
    void reportsRipgrepErrorExitCodeWithBlankStderr() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 2, false));

        var result = run(tool, Map.of("pattern", "x", "path", tempDir.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("ripgrep exited with code 2"));
    }

    @Test
    void reportsTimedOut() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 0, true));

        var result = run(tool, Map.of("pattern", "x", "path", tempDir.toString()), tempDir.toString());

        assertTrue(result.isError());
        assertTrue(result.content().contains("timed out"));
    }

    @Test
    void reportsEmptyMatchesAsSuccess() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 0, false));

        var result = run(tool, Map.of("pattern", "needle", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertEquals("No matches found", result.content());
    }

    @Test
    void formatsSingleMatch() throws Exception {
        var file = Files.writeString(tempDir.resolve("src.txt"), "needle here");
        var json = String.format("{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"%s\"},"
                                        + "\"line_number\":1,\"lines\":{\"text\":\"needle here\\n\"}}}",
                                        file.toString().replace("\\", "\\\\"));
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult(json, "", 0, false));

        var result = run(tool, Map.of("pattern", "needle", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("needle here"));
        assertEquals(1, result.details().get("matchCount"));
    }

    @Test
    void respectsLimit() throws Exception {
        var file = Files.writeString(tempDir.resolve("data.txt"), "one\ntwo\nthree\n");
        var stdout = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            var text = i == 0 ? "one" : i == 1 ? "two" : "three";
            stdout.append(String.format("{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"%s\"},"
                                            + "\"line_number\":%d,\"lines\":{\"text\":\"%s\\n\"}}}%n",
                                            file.toString().replace("\\", "\\\\"), i + 1, text));
        }
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult(stdout.toString(), "", 0,
                                        false));

        var result = run(tool, Map.of("pattern", ".", "path", tempDir.toString(), "limit", 2), tempDir.toString());

        assertFalse(result.isError());
        assertEquals(2, result.details().get("matchCount"));
        assertTrue(result.content().contains("limit reached"));
    }

    @Test
    void respectsContext() throws Exception {
        var file = Files.writeString(tempDir.resolve("ctx.txt"), "before\nneedle\nafter\n");
        var json = String.format("{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"%s\"},"
                                        + "\"line_number\":2,\"lines\":{\"text\":\"needle\\n\"}}}",
                                        file.toString().replace("\\", "\\\\"));
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult(json, "", 0, false));

        var result = run(tool, Map.of("pattern", "needle", "path", tempDir.toString(), "context", 1),
                                        tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("before"));
        assertTrue(result.content().contains("needle"));
        assertTrue(result.content().contains("after"));
    }

    @Test
    void truncatesLongMatchLines() throws Exception {
        var longLine = "a".repeat(600);
        var file = Files.writeString(tempDir.resolve("long.txt"), longLine);
        var json = String.format("{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"%s\"},"
                                        + "\"line_number\":1,\"lines\":{\"text\":\"%s\\n\"}}}",
                                        file.toString().replace("\\", "\\\\"), longLine);
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult(json, "", 0, false));

        var result = run(tool, Map.of("pattern", "a", "path", tempDir.toString()), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("truncated to 500 chars"));
        assertTrue((Boolean) result.details().get("linesTruncated"));
    }

    @Test
    void truncatesOutputWhenExceedsMaxChars() throws Exception {
        var longLine = "b".repeat(600);
        var file = Files.writeString(tempDir.resolve("huge.txt"), longLine + "\n");
        var stdout = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            stdout.append(String.format("{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"%s\"},"
                                            + "\"line_number\":%d,\"lines\":{\"text\":\"%s\\n\"}}}%n",
                                            file.toString().replace("\\", "\\\\"), i + 1, longLine));
        }
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult(stdout.toString(), "", 0,
                                        false));

        var result = run(tool, Map.of("pattern", "b", "path", tempDir.toString(), "limit", 200), tempDir.toString());

        assertFalse(result.isError());
        assertTrue(result.content().contains("Output truncated"));
        assertTrue((Boolean) result.details().get("truncated"));
    }
}
