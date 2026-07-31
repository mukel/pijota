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
        var lines = compose(msg, view, 12_000);

        // blank air + ┌ header + two railed body lines + └ footer
        assertEquals(5, lines.size(), visible(lines));
        assertEquals("", lines.get(0), "a framed block claims one blank line of air above itself");
        assertTrue(AnsiStripper.strip(lines.get(1)).startsWith("┌ $"), visible(lines)); // bash shows a "$" shell prompt
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
        String last = AnsiStripper.strip(lines.get(lines.size() - 1));
        assertTrue(last.contains("⋯ 2 more lines"), "excerpt cut at 5 with an amber cue: " + last);
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

        // 200 columns at width 80 (78 railed) → 3 physical rows, every one railed — a bare continuation row at
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
}
