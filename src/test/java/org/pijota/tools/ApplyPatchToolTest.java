package org.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class ApplyPatchToolTest {
    @TempDir
    Path tempDir;

    private static String patch(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void appliesSingleHunk() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "line1\nline2\nline3\n");

        var p = patch("--- a/f.txt", "+++ b/f.txt", "@@ -1,3 +1,3 @@", " line1", "-line2", "+LINE2", " line3");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("line1\nLINE2\nline3\n", Files.readString(path));
    }

    @Test
    void matchesByContextWhenLineNumbersDrift() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "a\nb\ntarget\nc\nd\n");

        // deliberately wrong @@ line numbers; application must locate the hunk by its context
        var p = patch("--- a/f.txt", "+++ b/f.txt", "@@ -99,3 +99,3 @@", " b", "-target", "+TARGET", " c");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("a\nb\nTARGET\nc\nd\n", Files.readString(path));
    }

    @Test
    void createsNewFileFromDevNull() throws Exception {
        var p = patch("--- /dev/null", "+++ b/new.txt", "@@ -0,0 +1,2 @@", "+hello", "+world");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("hello\nworld\n", Files.readString(tempDir.resolve("new.txt")));
    }

    @Test
    void deletesFileToDevNull() throws Exception {
        var path = tempDir.resolve("gone.txt");
        Files.writeString(path, "bye\n");

        var p = patch("--- a/gone.txt", "+++ /dev/null", "@@ -1,1 +0,0 @@", "-bye");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertFalse(Files.exists(path));
    }

    @Test
    void preservesCrlfLineEndings() throws Exception {
        var path = tempDir.resolve("crlf.txt");
        Files.writeString(path, "one\r\ntwo\r\nthree\r\n");

        var p = patch("--- a/crlf.txt", "+++ b/crlf.txt", "@@ -1,3 +1,3 @@", " one", "-two", "+TWO", " three");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("one\r\nTWO\r\nthree\r\n", Files.readString(path));
    }

    @Test
    void appliesMultipleFilesInOnePatch() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "x\n");
        Files.writeString(tempDir.resolve("b.txt"), "y\n");

        var p = patch("--- a/a.txt", "+++ b/a.txt", "@@ -1 +1 @@", "-x", "+X", "--- a/b.txt", "+++ b/b.txt",
                                        "@@ -1 +1 @@", "-y", "+Y");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("X\n", Files.readString(tempDir.resolve("a.txt")));
        assertEquals("Y\n", Files.readString(tempDir.resolve("b.txt")));
    }

    @Test
    void failsAtomicallyWhenAHunkDoesNotApply() throws Exception {
        var good = tempDir.resolve("good.txt");
        Files.writeString(good, "keep\n");
        var bad = tempDir.resolve("bad.txt");
        Files.writeString(bad, "actual content\n");

        // first file would apply, second hunk's context is absent → nothing should be written
        var p = patch("--- a/good.txt", "+++ b/good.txt", "@@ -1 +1 @@", "-keep", "+KEEP", "--- a/bad.txt",
                                        "+++ b/bad.txt", "@@ -1 +1 @@", "-not present", "+new");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertTrue(result.isError());
        assertEquals("keep\n", Files.readString(good), "no file is modified when any hunk fails");
        assertEquals("actual content\n", Files.readString(bad));
    }

    @Test
    void insertsViaPureAdditionHunkAnchoredByContext() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "a\nb\nc\n");

        var p = patch("--- a/f.txt", "+++ b/f.txt", "@@ -1,2 +1,3 @@", " a", "+INSERTED", " b");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("a\nINSERTED\nb\nc\n", Files.readString(path));
    }

    @Test
    void appliesMultipleHunksInOneFile() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "1\n2\n3\n4\n5\n");

        var p = patch("--- a/f.txt", "+++ b/f.txt", "@@ -1,3 +1,3 @@", " 1", "-2", "+TWO", " 3", "@@ -4,2 +4,2 @@",
                                        " 4", "-5", "+FIVE");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("1\nTWO\n3\n4\nFIVE\n", Files.readString(path));
    }

    @Test
    void stripsAbPrefixesAndTabTimestamps() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "old\n");

        var p = patch("--- a/f.txt\t2026-01-01 10:00:00", "+++ b/f.txt\t2026-01-01 10:00:01", "@@ -1 +1 @@", "-old",
                                        "+new");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("new\n", Files.readString(path));
    }

    @Test
    void ignoresNoNewlineAtEofMarker() throws Exception {
        var path = tempDir.resolve("f.txt");
        Files.writeString(path, "x\n");

        var p = patch("--- a/f.txt", "+++ b/f.txt", "@@ -1 +1 @@", "-x", "\\ No newline at end of file", "+y");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);

        assertFalse(result.isError(), result.content());
        assertEquals("y\n", Files.readString(path));
    }

    @Test
    void rejectsBlankPatch() {
        assertTrue(run(new ApplyPatchTool(), Map.of("patch", "   "), tempDir).isError());
        assertTrue(run(new ApplyPatchTool(), Map.of(), tempDir).isError());
    }

    @Test
    void rejectsPatchWithNoFileSections() {
        var result = run(new ApplyPatchTool(), Map.of("patch", "just some prose\nno headers here\n"), tempDir);
        assertTrue(result.isError());
    }

    @Test
    void rejectsMalformedHeader() {
        var p = "--- a/f.txt\nnot a +++ line\n";
        assertTrue(run(new ApplyPatchTool(), Map.of("patch", p), tempDir).isError());
    }

    @Test
    void errorsWhenTargetFileMissing() throws Exception {
        var p = patch("--- a/missing.txt", "+++ b/missing.txt", "@@ -1 +1 @@", "-a", "+b");
        var result = run(new ApplyPatchTool(), Map.of("patch", p), tempDir);
        assertTrue(result.isError());
        assertFalse(Files.exists(tempDir.resolve("missing.txt")));
    }

    @Test
    void theResultViewShowsTheAppliedDiffNotJustTheFileNames() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "one\ntwo\n");
        var tool = new ApplyPatchTool();
        var p = patch("--- a/a.txt", "+++ b/a.txt", "@@ -1,2 +1,2 @@", " one", "-two", "+TWO");
        var result = run(tool, Map.of("patch", p), tempDir);
        assertFalse(result.isError(), result.content());

        assertTrue(tool.hasResultView(), "apply_patch must advertise a view, or the TUI never calls renderResult");
        var view = tool.renderResult(new Tool.Args(Map.of("patch", p)), result, tempDir.toString());
        String body = String.join("\n", view.body());
        assertTrue(body.contains("-two") && body.contains("+TWO"), "the change itself is visible: " + body);
        assertTrue(view.summary().contains("a.txt"), "the summary still names the file: " + view.summary());
    }

    @Test
    void aHugePatchIsCappedWithACue() throws Exception {
        var sb = new StringBuilder("x\n".repeat(60));
        Files.writeString(tempDir.resolve("big.txt"), sb.toString());
        var lines = new java.util.ArrayList<String>(
                                        java.util.List.of("--- a/big.txt", "+++ b/big.txt", "@@ -1,60 +1,60 @@"));
        for (int i = 0; i < 60; i++) {
            lines.add("-x");
            lines.add("+y");
        }
        var tool = new ApplyPatchTool();
        var result = run(tool, Map.of("patch", patch(lines.toArray(String[]::new))), tempDir);
        assertFalse(result.isError(), result.content());

        var view = tool.renderResult(new Tool.Args(Map.of()), result, tempDir.toString());
        assertTrue(view.body().size() <= 21, "a large patch cannot flood the transcript: " + view.body().size());
        assertTrue(view.body().getLast().startsWith("⋯"), "the cut is announced: " + view.body().getLast());
    }
}
