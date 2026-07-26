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

    private static String visible(List<String> lines) {
        return lines.stream().map(AnsiStripper::strip).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void composesHeaderWithIconNameAndSummary() {
        var msg = new Message.ToolResult("id", "grep", "body", false, Map.of("matchCount", 5));
        var lines = ToolResultLines.compose(msg, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0, "●");

        String out = visible(lines);
        assertTrue(out.contains("grep"), out);
        assertTrue(out.contains("5 match(es)"), out);
    }

    @Test
    void usesExtensionViewSummaryAndBodyWhenPresent() {
        var msg = new Message.ToolResult("id", "bash", "ignored", false);
        var view = new Tool.ToolResultView("ran ok", List.of("line one", "line two"));
        var lines = ToolResultLines.compose(msg, view, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0, "●");

        String out = visible(lines);
        assertTrue(out.contains("ran ok"), out);
        assertTrue(out.contains("line one") && out.contains("line two"), out);
        assertTrue(out.contains("┌") && out.contains("└"), "bash output is framed: " + out);
        assertEquals(4, lines.size(), "┌ header + two body lines + └ footer");
    }

    @Test
    void bashExitCodeIsAppended() {
        var ok = new Message.ToolResult("id", "bash", "", false, Map.of("exitCode", 0));
        assertTrue(visible(ToolResultLines.compose(ok, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0,
                                        "●")).contains("exit 0"));

        var fail = new Message.ToolResult("id", "bash", "", true, Map.of("exitCode", 2));
        assertTrue(visible(ToolResultLines.compose(fail, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0,
                                        "●")).contains("exit 2"));
    }

    @Test
    void elapsedTimeShownOnlyForToolsTheUserWaitsOn() {
        var msg = new Message.ToolResult("id", "webfetch", "body", false);
        String slow = visible(ToolResultLines.compose(msg, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT,
                                        2500, "●"));
        assertTrue(slow.contains("2s"), slow);
        String fast = visible(ToolResultLines.compose(msg, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT,
                                        200, "●"));
        assertFalse(fast.contains("0s"), fast);
    }

    @Test
    void headerUsesToolGlyphAndErrorMarker() {
        var ok = new Message.ToolResult("id", "bash", "done", false);
        assertTrue(visible(ToolResultLines.compose(ok, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0,
                                        "$")).contains("$"), "the tool's glyph is shown");
        var fail = new Message.ToolResult("id", "bash", "boom", true);
        assertTrue(visible(ToolResultLines.compose(fail, null, null, AnsiTheme.dark(), "/cwd", PathDisplay.COMPACT, 0,
                                        "$")).contains("✖"), "a failure shows the error marker");
    }
}
