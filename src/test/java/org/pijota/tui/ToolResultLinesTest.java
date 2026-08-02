package org.pijota.tui;

import org.pijota.config.PathDisplay;
import org.pijota.messages.Message;
import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;
import org.pijota.tools.Tool;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultLinesTest {

    private static List<String> compose(Message.ToolResult msg, Tool.ToolResultView view, long elapsedMs) {
        return ToolResultLines.compose(msg, view, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, elapsedMs, "●",
                                        80);
    }

    private static String visible(List<String> lines) {
        return lines.stream().map(AnsiStripper::strip).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void quietToolIsOneLineWithDimMetadata() {
        var msg = new Message.ToolResult("id", "grep", "body", false, Map.of("matchCount", 5));
        var lines = compose(msg, null, 0);

        assertEquals(1, lines.size(), "no body → the header line is the whole block");
        String out = AnsiStripper.strip(lines.get(0));
        assertTrue(out.startsWith("● "), out);
        assertTrue(out.contains("grep"), out);
        assertTrue(out.contains("· 5 matches"), "metadata is ·-joined and pluralized: " + out);
    }

    @Test
    void toolWithBodyIsFramedWithRailAndFooter() {
        var msg = new Message.ToolResult("id", "bash", "ignored", false, Map.of("exitCode", 0));
        var view = new Tool.ToolResultView("2 lines", List.of("line one", "line two"));
        // bash's own glyph, as the TUI passes it: ToolGlyphs.RUN is the "$" the shell prompt already uses.
        var lines = ToolResultLines.compose(msg, view, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 12_000, "$",
                                        80);

        // blank air + ┌ header + two railed body lines + └ footer
        assertEquals(5, lines.size(), visible(lines));
        assertEquals("", lines.get(0), "a framed block claims one blank line of air above itself");
        // The tool's own glyph opens the block -- for bash the "$" shell prompt, which is also its glyph, so it is
        // printed once. A ┌ here would say only "a block starts", which the rail below already says.
        assertEquals("$", AnsiStripper.strip(lines.get(1)), visible(lines));
        assertTrue(AnsiStripper.strip(lines.get(2)).startsWith("│ line one"), visible(lines));
        assertTrue(AnsiStripper.strip(lines.get(3)).startsWith("│ line two"), visible(lines));
        String foot = AnsiStripper.strip(lines.get(4));
        assertTrue(foot.startsWith("└ "), foot);
        assertTrue(foot.contains("2 lines") && foot.contains("exit 0") && foot.contains("12"),
                                        "footer carries summary · exit · elapsed: " + foot);
    }

    @Test
    void failureKeepsOneLineHeaderAndRailsTheExcerpt() {
        String error = "error[E0308]: mismatched types\nline2\nline3\nline4\nline5\nline6\nline7";
        var msg = new Message.ToolResult("id", "bash", error, true, Map.of("exitCode", 101));
        var lines = compose(msg, null, 4_000);

        String header = AnsiStripper.strip(lines.get(1));
        assertTrue(header.startsWith("✖ $"), header); // bash shows a "$" shell prompt, not the word
        assertTrue(header.contains("exit 101"), header);
        assertFalse(header.contains("error[E0308]"), "the excerpt lives in the body, never the header: " + header);
        assertTrue(AnsiStripper.strip(lines.get(2)).startsWith("│ error[E0308]"), visible(lines));
        String cue = AnsiStripper.strip(lines.get(lines.size() - 2));
        assertTrue(cue.contains("⋯ 2 more lines"), "excerpt cut at 5 with an amber cue: " + cue);
        // The ✖ opened the block in place of ┌, so the block still closes. It used to trail off into a rail that
        // never ended, which read as output still arriving.
        assertEquals("└", AnsiStripper.strip(lines.get(lines.size() - 1)), visible(lines));
    }

    /** A blank first line of error content used to render as a bare │: a rail with nothing on it. */
    @Test
    void aFailureBodyDoesNotOpenOrCloseOnAnEmptyRail() {
        var msg = new Message.ToolResult("id", "bash", "\n\n[Command exited with code 3]\n\n", true,
                                        Map.of("exitCode", 3));
        var lines = compose(msg, null, 0);

        assertEquals("│ [Command exited with code 3]", AnsiStripper.strip(lines.get(2)), visible(lines));
        assertEquals("└", AnsiStripper.strip(lines.get(3)), visible(lines));
        assertEquals(4, lines.size(), "air, header, one body row, closer -- nothing blank: " + visible(lines));
    }

    @Test
    void shortErrorHasNoTruncationCue() {
        var msg = new Message.ToolResult("id", "read", "no such file", true);
        var lines = compose(msg, null, 0);

        String out = visible(lines);
        assertTrue(out.contains("✖"), out);
        assertTrue(out.contains("│ no such file"), out);
        assertFalse(out.contains("⋯"), out);
    }

    @Test
    void elapsedTimeShownOnlyForToolsTheUserWaitsOn() {
        var msg = new Message.ToolResult("id", "webfetch", "body", false);
        assertTrue(visible(compose(msg, null, 2500)).contains("2s"));
        assertFalse(visible(compose(msg, null, 200)).contains("0s"));
    }

    /**
     * Bash's run glyph and the shell prompt it goes by are the same character, so a quiet bash call opened
     * {@code $ $ touch x}. One tool, one mark.
     */
    @Test
    void aGlyphIsNotRepeatedWhenItIsAlreadyTheToolsName() {
        assertEquals("", ToolResultLines.glyphPrefix("$", "bash"));
        assertEquals("○ ", ToolResultLines.glyphPrefix("○", "read"));

        var quiet = new Message.ToolResult("id", "bash", "", false, Map.of("exitCode", 0));
        String line = AnsiStripper.strip(ToolResultLines.compose(quiet, null, null, AnsiTheme.dark(), "/cwd",
                                        PathDisplay.COMPACT, 0, "$", 80).get(0));
        assertTrue(line.startsWith("$ "), line);
        assertFalse(line.startsWith("$ $"), "one $, not two: " + line);
    }

    @Test
    void headerUsesToolGlyphAndErrorMarker() {
        var ok = new Message.ToolResult("id", "bash", "done", false);
        assertTrue(visible(ToolResultLines.compose(ok, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0,
                                        "$", 80)).contains("$"), "the tool's glyph is shown");
        var fail = new Message.ToolResult("id", "bash", "boom", true);
        assertTrue(visible(compose(fail, null, 0)).contains("✖"), "a failure shows the error marker");
    }

    @Test
    void longBodyLinesKeepTheRailOnEveryWrappedRow() {
        var msg = new Message.ToolResult("id", "bash", "ignored", false);
        var view = new Tool.ToolResultView("1 line", List.of("x".repeat(200)));
        var lines = compose(msg, view, 0);

        // 200 columns at width 80 (78 railed) → 3 physical rows, every one railed; a bare continuation row at
        // column zero would visually break the block open.
        long railed = lines.stream().map(AnsiStripper::strip).filter(l -> l.startsWith("│ ")).count();
        assertEquals(3, railed, visible(lines));
        for (String line : lines)
            assertTrue(AnsiWidth.visibleWidth(line) <= 80, "railed row exceeds the terminal width: " + line);
    }

    @Test
    void bodyRowsNeverStackDoubleRails() {
        var msg = new Message.ToolResult("id", "todo", "ignored", false);
        var view = new Tool.ToolResultView("1 item", List.of("│ [✓] ship it"));
        var lines = compose(msg, view, 0);

        String body = AnsiStripper.strip(lines.get(2));
        assertTrue(body.startsWith("│ [✓]"), "todo lines keep their own rail, un-doubled: " + body);
    }

    @Test
    void diffBodyLinesAreColoredByChangeMarker() {
        var theme = AnsiTheme.dark();
        // Keyed on content, not tool name, so any tool emitting a unified diff (apply_patch today) reads as a diff.
        assertNotEquals(ToolResultLines.renderToolBodyLine("+added", theme, true),
                                        ToolResultLines.renderToolBodyLine("-removed", theme, true),
                                        "additions and deletions must not share a color");
        assertTrue(ToolResultLines.renderToolBodyLine("+added", theme, true).contains(theme.style("diffAdd")));
        assertTrue(ToolResultLines.renderToolBodyLine("-removed", theme, true).contains(theme.style("diffDelete")));
        assertTrue(ToolResultLines.renderToolBodyLine("@@ -1 +1 @@", theme, true).contains(theme.style("diffHunk")));
        // The +++/--- file headers are hunk chrome, not content changes.
        assertTrue(ToolResultLines.renderToolBodyLine("+++ b/x.txt", theme, true).contains(theme.style("diffHunk")));
        assertTrue(ToolResultLines.renderToolBodyLine("--- a/x.txt", theme, true).contains(theme.style("diffHunk")));
    }

    /**
     * The reason diff colouring is decided per block and not per line: {@code -} and {@code +} start plenty of ordinary
     * output. Per-line, every {@code -rw-r--r--} row of an {@code ls -l} came out in error red, which reads as half the
     * listing having failed.
     */
    @Test
    void ordinaryCommandOutputIsNeverColouredAsADiff() {
        var msg = new Message.ToolResult("id", "bash", "ok", false, Map.of("exitCode", 0));
        var rendered = new Tool.ToolResultView("4 lines", List.of("-rw-r--r-- 1 mukel mukel 133 calc.py",
                                        "--- a listing, not a diff", "+++ nor is this", "+1 more"));
        var lines = compose(msg, rendered, 0);

        var theme = AnsiTheme.dark();
        String listing = String.join("\n", lines);
        assertFalse(listing.contains(theme.style("diffDelete")), "an ls -l row is not a deletion: " + visible(lines));
        assertFalse(listing.contains(theme.style("diffAdd")), visible(lines));
        assertFalse(listing.contains(theme.style("diffHunk")), "no hunk header, so nothing is diff chrome");
    }

    /** ... and a body that really is a diff still reads as one. */
    @Test
    void aBodyWithAHunkHeaderStillReadsAsADiff() {
        var msg = new Message.ToolResult("id", "apply_patch", "ok", false);
        var rendered = new Tool.ToolResultView("patched", List.of("@@ -1,2 +1,3 @@", "-old line", "+new line"));
        var lines = compose(msg, rendered, 0);

        var theme = AnsiTheme.dark();
        String patch = String.join("\n", lines);
        assertTrue(patch.contains(theme.style("diffHunk")), visible(lines));
        assertTrue(patch.contains(theme.style("diffDelete")), visible(lines));
        assertTrue(patch.contains(theme.style("diffAdd")), visible(lines));
    }

    @Test
    void prettyJsonResultsSummarizeAsContentNotALoneBrace() {
        // MCP tools commonly return pretty-printed JSON; its first line is "{", which as a summary says nothing.
        var msg = new Message.ToolResult("id", "mcp__srv__tool", "{\n  \"thoughtNumber\": 1,\n  \"done\": true\n}",
                                        false);
        String summary = ToolResultLines.toolResultSummary(msg);
        assertTrue(summary.contains("thoughtNumber"), "the summary shows real content: " + summary);
        assertFalse(summary.equals("{"), "never just the opening brace");
    }

    @Test
    void aLongCommandStaysInsideTheBlockInsteadOfOverflowingToColumnZero() {
        // At 52 columns a long shell command used to be wrapped by the terminal, so its continuation started at
        // column zero -- outside the frame, breaking the box open right where the command is hardest to read.
        String command = "python -c \"from calc import divide; print(divide(10,2)); print(divide(1,0))\"";
        var msg = new Message.ToolResult("call-1", "bash", "5.0", false, Map.of("command", command, "exitCode", 0));
        var view = Tool.ToolResultView.of("1 line", List.of("5.0"));

        String argsJson = "{\"command\": \"" + command.replace("\"", "\\\"") + "\"}";
        var lines = ToolResultLines.compose(msg, view, () -> argsJson, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0,
                                        "$", 52);

        var visible = lines.stream().map(AnsiStripper::strip).filter(l -> !l.isBlank()).toList();
        for (String line : visible) {
            assertTrue(AnsiWidth.visibleWidth(line) <= 52, "row overflows the terminal: [" + line + "]");
            // Every row of the block is framed: the opener (the tool's own glyph), the rail, or the footer. None
            // dangles bare at column zero.
            assertTrue(line.startsWith("$") || line.startsWith("│") || line.startsWith("└"),
                                            "row escaped the frame: [" + line + "] in " + visible);
        }
        assertTrue(visible.size() > 3, "the command really did need more than one row: " + visible);
    }

    @Test
    void aLongSummaryDoesNotLetTheFooterEscapeTheBlockItCloses() {
        // The footer's summary is the tool's first output line (Util.firstLine), which is not length-capped. It was
        // the one line of the block still left for the terminal to wrap, so a single long line of output produced a
        // closing row that dangled at column zero -- outside the box it exists to close.
        String longFirstLine = "Successfully replaced 1 block(s) in a file with a very long descriptive path and a "
                                        + "message that keeps going well past the width of any sensible terminal";
        var msg = new Message.ToolResult("call-1", "edit", longFirstLine + "\nsecond line", false, Map.of());
        var view = Tool.ToolResultView.of(longFirstLine, List.of("done"));

        var lines = ToolResultLines.compose(msg, view, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0, "●", 60);

        var visible = lines.stream().map(AnsiStripper::strip).filter(l -> !l.isBlank()).toList();
        for (String line : visible)
            assertTrue(AnsiWidth.visibleWidth(line) <= 60, "row overflows the terminal: [" + line + "]");
        var footerRows = visible.stream().dropWhile(l -> !l.startsWith("└")).toList();
        assertTrue(footerRows.size() > 1, "the summary really did need more than one row: " + visible);
        for (String row : footerRows.subList(1, footerRows.size()))
            assertTrue(row.startsWith("  "), "a footer continuation is indented, not at column zero: [" + row + "]");
    }

    /**
     * An interrupt clears the in-flight call before its result lands, so the request arguments are gone by the time the
     * line is composed. It then read "✖ $ · exit 143": a failure with no way to tell which command failed. The result
     * records the command it ran, which names it just as well.
     */
    @Test
    void anInterruptedCommandStillSaysWhichCommandItWas() {
        var msg = new Message.ToolResult("call-1", "bash", "", true, Map.of("command", "sleep 60", "exitCode", 143));

        // null args: exactly what the composer gets once the in-flight entry has been evicted.
        var lines = ToolResultLines.compose(msg, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0, "$", 80);

        String out = visible(lines);
        assertTrue(out.contains("sleep 60"), "the interrupted command must still be named: " + out);
        assertTrue(out.contains("143"), "and its exit code kept: " + out);
    }

    @Test
    void requestArgumentsStillWinWhenTheyArePresent() {
        var msg = new Message.ToolResult("call-1", "bash", "", false, Map.of("command", "from-details"));
        var lines = ToolResultLines.compose(msg, null, () -> "{\"command\": \"from-args\"}", AnsiTheme.dark(), "/cwd",
                                        PathDisplay.COMPACT, 0, "$", 80);

        String out = visible(lines);
        assertTrue(out.contains("from-args"), "the fallback must not override what was actually requested: " + out);
        assertFalse(out.contains("from-details"), out);
    }

    // ---- the user's own ! commands, in the same grammar -------------------------------------------------------

    private static List<String> shell(String cmd, String out, int exit, long elapsedMs, int width) {
        return ToolResultLines.shellBlock("!", cmd, out, exit, elapsedMs, AnsiTheme.dark(), width);
    }

    /**
     * A user's command reads as a block, like a tool call's does. Its output used to print bare at column zero, with
     * nothing separating it from the prose above and nothing saying where one command's output ended.
     */
    @Test
    void aUserCommandReadsAsABlock() {
        var out = shell("git push", "To github.com:x/y.git\n   aaa..bbb  main\n", 0, 0, 80);

        assertEquals("", out.get(0), "a block claims one blank line of air");
        assertEquals("! git push", AnsiStripper.strip(out.get(1)), "! says you ran it, not the model");
        assertEquals("│ To github.com:x/y.git", AnsiStripper.strip(out.get(2)));
        assertEquals("│    aaa..bbb  main", AnsiStripper.strip(out.get(3)));
        assertEquals("└ 2 lines · exit 0", AnsiStripper.strip(out.get(4)));
    }

    /**
     * The header goes through the same wrapper a tool header does. Hand-rolled, it was appended unwrapped, so a long
     * command -- which is exactly what people type after {@code !} -- broke out to column zero and split the box open.
     */
    @Test
    void aLongUserCommandStaysInsideItsBlock() {
        String command = "echo " + "a".repeat(90) + " | head -c 200";
        var out = shell(command, "done\n", 0, 0, 60);

        for (String line : out.stream().map(AnsiStripper::strip).filter(l -> !l.isBlank()).toList()) {
            assertTrue(AnsiWidth.visibleWidth(line) <= 60, "row overflows the terminal: [" + line + "]");
            assertTrue(line.startsWith("!") || line.startsWith("│") || line.startsWith("└"),
                                            "row escaped the frame: [" + line + "] in " + visible(out));
        }
    }

    /** The count is of logical lines. Counting rendered rows made one long line of output report "3 lines". */
    @Test
    void theFooterCountsOutputLinesNotTheRowsTheyWrapTo() {
        var out = shell("cat wide.txt", "x".repeat(200) + "\n", 0, 0, 60);
        assertEquals("1 line", AnsiStripper.strip(out.getLast()).replaceAll("^└ | · exit 0$", ""),
                                        visible(out) + " (200 chars at width 60 is one line, three rows)");
    }

    /** Same three shapes as a tool result, through the same helpers. */
    @Test
    void aUserCommandTakesTheSameThreeShapesAToolResultDoes() {
        assertEquals("! touch x · exit 0", AnsiStripper.strip(shell("touch x", "", 0, 0, 80).get(1)),
                                        "no output: one line carrying its own metadata");
        assertEquals(2, shell("touch x", "", 0, 0, 80).size());

        var failed = shell("ls /nope", "ls: cannot access '/nope'\n", 2, 0, 80);
        assertEquals("! ls /nope · exit 2", AnsiStripper.strip(failed.get(1)), "a failure keeps its status up top");
        assertEquals("└", AnsiStripper.strip(failed.getLast()), "and closes bare");

        assertEquals("! sleep 9 · interrupted", AnsiStripper.strip(shell("sleep 9", "", 143, 0, 80).get(1)));
        assertTrue(AnsiStripper.strip(shell("build", "done\n", 0, 12_000, 80).getLast()).contains("12s"));
    }

    /** !! keeps the command out of the conversation; the mark is the only place that difference shows. */
    @Test
    void theMarkSaysWhetherTheCommandJoinedTheConversation() {
        assertTrue(AnsiStripper.strip(ToolResultLines.shellBlock("!!", "ls", "", 0, 0, AnsiTheme.dark(), 80).get(1))
                                        .startsWith("!! ls"));
    }
}
