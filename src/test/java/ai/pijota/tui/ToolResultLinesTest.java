package ai.pijota.tui;

import ai.pijota.messages.Message;
import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;
import ai.pijota.tools.Tool;

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
        var lines = ToolResultLines.compose(msg, null, null, AnsiTheme.dark(), "/cwd", false);

        String out = visible(lines);
        assertTrue(out.contains("grep"), out);
        assertTrue(out.contains("5 match(es)"), out);
    }

    @Test
    void usesExtensionViewSummaryAndBodyWhenPresent() {
        var msg = new Message.ToolResult("id", "bash", "ignored", false);
        var view = new Tool.ToolResultView("ran ok", List.of("line one", "line two"));
        var lines = ToolResultLines.compose(msg, view, null, AnsiTheme.dark(), "/cwd", false);

        String out = visible(lines);
        assertTrue(out.contains("ran ok"), out);
        assertTrue(out.contains("line one") && out.contains("line two"), out);
        assertEquals(3, lines.size(), "header + two body lines");
    }

    @Test
    void bashExitCodeIsAppended() {
        var ok = new Message.ToolResult("id", "bash", "", false, Map.of("exitCode", 0));
        assertTrue(visible(ToolResultLines.compose(ok, null, null, AnsiTheme.dark(), "/cwd", false))
                                        .contains("exit 0"));

        var fail = new Message.ToolResult("id", "bash", "", true, Map.of("exitCode", 2));
        assertTrue(visible(ToolResultLines.compose(fail, null, null, AnsiTheme.dark(), "/cwd", false))
                                        .contains("exit 2"));
    }

    @Test
    void errorIconForFailedTool() {
        assertEquals("✖", ToolResultLines.toolIcon("bash", true));
        assertEquals("●", ToolResultLines.toolIcon("mystery", false));
    }
}
