package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class EditToolTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesExactSingleEdit() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "hello world\n");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "hello", "newText", "hi"))), tempDir);

        assertFalse(result.isError());
        assertEquals("hi world\n", Files.readString(path));
    }

    @Test
    void appliesMultipleEdits() throws Exception {
        var path = tempDir.resolve("multi.txt");
        Files.writeString(path, "alpha beta gamma");

        var edits = List.of(Map.of("oldText", "alpha", "newText", "A"), Map.of("oldText", "gamma", "newText", "C"));
        var result = run(new EditTool(), Map.of("path", path.toString(), "edits", edits), tempDir);

        assertFalse(result.isError());
        assertEquals("A beta C", Files.readString(path));
    }

    @Test
    void rejectsAmbiguousOldText() throws Exception {
        var path = tempDir.resolve("dup.txt");
        Files.writeString(path, "abc abc abc");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "abc ", "newText", "xyz "))), tempDir);

        assertTrue(result.isError());
        assertTrue(result.content().contains("unique") || result.content().contains("matches"),
                                        "should reject text that appears multiple times: " + result.content());
    }

    @Test
    void rejectsOverlappingEdits() throws Exception {
        var path = tempDir.resolve("overlap.txt");
        Files.writeString(path, "abcdef");

        var edits = List.of(Map.of("oldText", "abc", "newText", "x"), Map.of("oldText", "cde", "newText", "y"));
        var result = run(new EditTool(), Map.of("path", path.toString(), "edits", edits), tempDir);

        assertTrue(result.isError());
        assertTrue(result.content().contains("overlap"));
    }

    @Test
    void rejectsEmptyOldText() throws Exception {
        var path = tempDir.resolve("e.txt");
        Files.writeString(path, "text");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "", "newText", "replacement"))), tempDir);

        assertTrue(result.isError());
    }

    @Test
    void rejectsMissingPath() {
        var result = run(new EditTool(), Map.of("edits", List.of(Map.of("oldText", "a", "newText", "b"))), tempDir);

        assertTrue(result.isError());
        assertTrue(result.content().contains("No path"));
    }

    @Test
    void rejectsUnwritableFile() throws Exception {
        var path = tempDir.resolve("ro.txt");
        Files.writeString(path, "hello");
        var file = path.toFile();
        file.setWritable(false);
        try {
            var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                            List.of(Map.of("oldText", "hello", "newText", "hi"))), tempDir);

            assertTrue(result.isError());
            assertTrue(result.content().contains("Cannot read/write"));
        } finally {
            file.setWritable(true);
        }
    }

    @Test
    void preservesLineEndings() throws Exception {
        var path = tempDir.resolve("crlf.txt");
        Files.writeString(path, "old\r\nsecond\r\n");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "old", "newText", "new"))), tempDir);

        assertFalse(result.isError());
        assertEquals("new\r\nsecond\r\n", Files.readString(path));
    }

    @Test
    void stripsBomBeforeMatching() throws Exception {
        var path = tempDir.resolve("bom.txt");
        Files.writeString(path, "﻿hello world");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "hello", "newText", "hi"))), tempDir);

        assertFalse(result.isError());
        assertFalse(Files.readString(path).startsWith("﻿"));
        assertEquals("hi world", Files.readString(path));
    }

    @Test
    void legacySingleEditOutsideEditsArray() throws Exception {
        var path = tempDir.resolve("legacy.txt");
        Files.writeString(path, "foo bar");

        var args = new HashMap<String, Object>();
        args.put("path", path.toString());
        args.put("oldText", "foo");
        args.put("newText", "baz");
        var result = run(new EditTool(), args, tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("baz bar", Files.readString(path));
    }

    @Test
    void editsAsJsonString() throws Exception {
        var path = tempDir.resolve("json.txt");
        Files.writeString(path, "hello");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        "[{\"oldText\":\"hello\",\"newText\":\"hi\"}]"), tempDir);

        assertFalse(result.isError());
        assertEquals("hi", Files.readString(path));
    }

    @Test
    void fuzzyTrailingWhitespaceMatch() throws Exception {
        var path = tempDir.resolve("ws.txt");
        Files.writeString(path, "hello \nworld");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "hello ", "newText", "hi"))), tempDir);

        assertFalse(result.isError());
        assertEquals("hi\nworld", Files.readString(path));
    }

    @Test
    void fuzzyMatchesAcrossIndentationDifference() throws Exception {
        // file is tab-indented; the model reproduces the line with spaces — not a literal substring, so only the
        // whitespace-insensitive fuzzy path can match it
        var path = tempDir.resolve("indent.py");
        Files.writeString(path, "if x:\n\tdo_thing()\n");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "    do_thing()", "newText", "\tdo_other()"))),
                                        tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("if x:\n\tdo_other()\n", Files.readString(path));
    }

    @Test
    void fuzzyMatchesMultiLineBlockAtDifferentIndent() throws Exception {
        var path = tempDir.resolve("block.py");
        Files.writeString(path, "def g():\n    a = 1\n    b = 2\n    return a + b\n");

        // dedented two-line block from the model
        var edits = List.of(Map.of("oldText", "a = 1\nb = 2", "newText", "    a = 10\n    b = 20"));
        var result = run(new EditTool(), Map.of("path", path.toString(), "edits", edits), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("def g():\n    a = 10\n    b = 20\n    return a + b\n", Files.readString(path));
    }

    @Test
    void fuzzyEditDoesNotRewriteTheRestOfTheFile() throws Exception {
        // a line elsewhere has trailing whitespace + a smart quote; a fuzzy edit must leave it byte-for-byte intact
        var path = tempDir.resolve("local.txt");
        Files.writeString(path, "keep me   \ntarget line\n‘untouched’\n");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "  target line  ", "newText", "changed"))), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("keep me   \nchanged\n‘untouched’\n", Files.readString(path));
    }

    @Test
    void ambiguousFuzzyMatchIsRejected() throws Exception {
        var path = tempDir.resolve("dup.txt");
        Files.writeString(path, "  foo\n    foo\nbar\n"); // two lines canonicalize to "foo"

        // trailing space => not a literal substring (forces the fuzzy path), which then finds two candidates
        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "foo ", "newText", "baz"))), tempDir);

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"), result.content());
    }

    @Test
    void unicodeNormalizationFuzzyMatch() throws Exception {
        var path = tempDir.resolve("uni.txt");
        Files.writeString(path, "‘ello world");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "‘ello", "newText", "Hello"))), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("Hello world", Files.readString(path));
    }

    @Test
    void reportsNoChangesWhenReplacementIdentical() throws Exception {
        var path = tempDir.resolve("same.txt");
        Files.writeString(path, "hello");

        var result = run(new EditTool(), Map.of("path", path.toString(), "edits",
                                        List.of(Map.of("oldText", "hello", "newText", "hello"))), tempDir);

        assertTrue(result.isError());
        assertTrue(result.content().contains("No changes"));
    }

    @Test
    void renderResultReturnsContentOnError() {
        var tool = new EditTool();
        var result = Tool.ToolResult.error("err");
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("err", view.summary());
    }

    @Test
    void renderResultShowsDiffPreview() {
        var tool = new EditTool();
        var result = Tool.ToolResult.success("ok", Map.of("diff", "-1 old\n+1 new"));
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("ok", view.summary());
        assertTrue(view.body().contains("-1 old"));
        assertTrue(view.body().contains("+1 new"));
    }

    @Test
    void renderResultTruncatesLongDiff() {
        var tool = new EditTool();
        var diff = IntStream.range(0, 25).mapToObj(i -> (i % 2 == 0 ? "-" : "+") + i + " line")
                                        .collect(Collectors.joining("\n"));
        var result = Tool.ToolResult.success("ok", Map.of("diff", diff));
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/tmp");
        assertEquals("ok", view.summary());
        assertTrue(view.body().contains("… 5 more changed lines"));
    }
}
