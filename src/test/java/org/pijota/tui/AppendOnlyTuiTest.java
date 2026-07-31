package org.pijota.tui;

import org.pijota.extensions.KeyChord;
import org.pijota.messages.Message;
import org.pijota.theme.AnsiTheme;
import org.pijota.tools.GrepTool;
import org.pijota.tools.ReadTool;
import org.pijota.tools.WhoamiTool;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppendOnlyTuiTest {

    @Test
    void detectsNoColorTerminalTypes() {
        assertTrue(AppendOnlyTui.isNoColorTerminal("dumb"), "plain dumb terminal → no color");
        assertFalse(AppendOnlyTui.isNoColorTerminal("dumb-color"), "dumb-color is JLine's color-capable dumb terminal");
        assertFalse(AppendOnlyTui.isNoColorTerminal("xterm-256color"));
        assertFalse(AppendOnlyTui.isNoColorTerminal(""));
        assertFalse(AppendOnlyTui.isNoColorTerminal(null));
    }

    @Test
    void collapseStatusLinesCapsWithAffordanceOrShowsAll() {
        var five = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("a", "b", "c", "… 2 more · Ctrl+O to expand"), AppendOnlyTui.collapse(five, false, 3)); // collapsed
                                                                                                                     // →
                                                                                                                     // first
                                                                                                                     // 3
                                                                                                                     // +
                                                                                                                     // expand
                                                                                                                     // hint
        assertEquals(List.of("a", "b", "c", "d", "e", "… Ctrl+O to collapse"), AppendOnlyTui.collapse(five, true, 3)); // expanded
                                                                                                                       // →
                                                                                                                       // all
                                                                                                                       // +
                                                                                                                       // collapse
                                                                                                                       // hint
        assertEquals(List.of("a", "b"), AppendOnlyTui.collapse(List.of("a", "b"), false, 3)); // under cap → untouched
        assertEquals(List.of("a", "b"), AppendOnlyTui.collapse(List.of("a", "b"), true, 3));
    }

    @Test
    void compactSessionDetailNull() {
        assertEquals("", AppendOnlyTui.compactSessionDetail(null));
    }

    @Test
    void compactSessionDetailBlank() {
        assertEquals("", AppendOnlyTui.compactSessionDetail("  "));
    }

    @Test
    void compactSessionDetailTrimsUuidFromEnd() {
        var result = AppendOnlyTui.compactSessionDetail("hello world  12345678-1234");
        assertEquals("hello world", result);
    }

    @Test
    void compactSessionDetailNoUuid() {
        assertEquals("simple text", AppendOnlyTui.compactSessionDetail("simple text"));
    }

    @Test
    void compactModelNameShortProvider() {
        var result = PromptPresenter.compactModelName("openai", "gpt-4o");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void compactModelNameLongProvider() {
        var result = PromptPresenter.compactModelName("anthropic", "claude-sonnet-4-5-20250929");
        assertNotNull(result);
    }

    @Test
    void isEditWriteForEdit() {
        assertTrue(AppendOnlyTui.isEditWrite(new Message.ToolResult("id", "edit", "content", false)));
    }

    @Test
    void isEditWriteForWrite() {
        assertTrue(AppendOnlyTui.isEditWrite(new Message.ToolResult("id", "write", "content", false)));
    }

    @Test
    void isEditWriteForRead() {
        assertFalse(AppendOnlyTui.isEditWrite(new Message.ToolResult("id", "read", "content", false)));
    }

    @Test
    void isEditWriteForBash() {
        assertFalse(AppendOnlyTui.isEditWrite(new Message.ToolResult("id", "bash", "content", false)));
    }

    @Test
    void toolsProvideTheirOwnGlyph() {
        assertEquals("📖", new ReadTool().glyph());
        assertEquals("🔍", new GrepTool().glyph());
        assertEquals("●", new WhoamiTool().glyph(), "tools without a custom glyph use the bullet");
    }

    @Test
    void padRightPadsString() {
        var result = AppendOnlyTui.padRight("hi", 10);
        assertTrue(result.startsWith("hi"));
        assertTrue(result.length() >= 10);
    }

    @Test
    void padRightShorterThanWidth() {
        var result = AppendOnlyTui.padRight("hello", 3);
        assertEquals("hello", result);
    }

    @Test
    void insidePromptCodeBlockWithOpenFence() {
        assertTrue(PromptPresenter.insidePromptCodeBlock(List.of("text", "```java", "code")));
    }

    @Test
    void insidePromptCodeBlockWithClosedFence() {
        assertFalse(PromptPresenter.insidePromptCodeBlock(List.of("text", "```java", "code", "```", "more")));
    }

    @Test
    void insidePromptCodeBlockWithoutFence() {
        assertFalse(PromptPresenter.insidePromptCodeBlock(List.of("text", "no code block")));
    }

    @Test
    void insidePromptCodeBlockWithTildeFence() {
        assertTrue(PromptPresenter.insidePromptCodeBlock(List.of("~~~bash", "cmd")));
        assertFalse(PromptPresenter.insidePromptCodeBlock(List.of("~~~bash", "cmd", "~~~")));
    }

    @Test
    void showExtensionStatusLineNormal() {
        assertTrue(PromptPresenter.showExtensionStatusLine("hello"));
    }

    @Test
    void showExtensionStatusLineFiltered() {
        assertFalse(PromptPresenter.showExtensionStatusLine("something filtered"));
    }

    @Test
    void showExtensionStatusLineSecured() {
        assertFalse(PromptPresenter.showExtensionStatusLine("secured status"));
    }

    @Test
    void showExtensionStatusLineEmpty() {
        assertTrue(PromptPresenter.showExtensionStatusLine(""));
    }

    @Test
    void showExtensionStatusLineNull() {
        assertFalse(PromptPresenter.showExtensionStatusLine(null));
    }

    @Test
    void mergeExtensionLinesFiltersBeforeCollapseCountsVisibleLines() {
        var merged = PromptPresenter.mergeExtensionLines(
                                        List.of("a", "b", "c", "something filtered", "secured status"));

        assertEquals(List.of("a", "b", "c"), merged);
        assertEquals(List.of("a", "b", "c"), AppendOnlyTui.collapse(merged, false, 3));
    }

    @Test
    void bareHelpInputsOpenHelpInsteadOfPromptingModel() {
        assertTrue(AppendOnlyTui.isBareHelp("help"));
        assertFalse(AppendOnlyTui.isBareHelp("help remote"));
        assertFalse(AppendOnlyTui.isBareHelp("helpful"));
        assertFalse(AppendOnlyTui.isBareHelp("please help"));
    }

    @Test
    void vimQuitInputsExitInsteadOfPromptingModel() {
        for (String q : new String[] {":q", ":q!", ":wq", ":wq!", ":x", ":quit", ":qa", ":qa!"})
            assertTrue(AppendOnlyTui.isVimQuit(q), q + " should quit");
        // a leading space is the literal escape (consumed by the caller's `literal` flag), so a spaced :q is just text
        for (String notQuit : new String[] {" :q", ":q ", ":query", ":quack", "q", "quit", ":qq", "how do I :q"})
            assertFalse(AppendOnlyTui.isVimQuit(notQuit), notQuit + " should NOT quit");
    }

    @Test
    void isReservedKeyEnter() {
        assertTrue(AppendOnlyTui.isReservedKey(Key.Special.ENTER));
    }

    @Test
    void isReservedKeyEsc() {
        assertTrue(AppendOnlyTui.isReservedKey(Key.Special.ESC));
    }

    @Test
    void isReservedKeyChar() {
        assertFalse(AppendOnlyTui.isReservedKey(new Key.Char('a')));
    }

    @Test
    void toKeyChordEnter() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.ENTER);
        assertEquals(KeyChord.Key.ENTER, chord.key());
    }

    @Test
    void toKeyChordEsc() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.ESC);
        assertEquals(KeyChord.Key.ESC, chord.key());
    }

    @Test
    void toKeyChordChar() {
        assertNull(AppendOnlyTui.toKeyChord(new Key.Char('a')));
    }

    @Test
    void toKeyChordCtrlC() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.CTRL_C);
        assertTrue(chord.modifiers().contains(KeyChord.Modifier.CTRL));
        assertEquals(KeyChord.Key.C, chord.key());
    }

    @Test
    void toKeyChordCtrlK() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.CTRL_K);
        assertTrue(chord.modifiers().contains(KeyChord.Modifier.CTRL));
        assertEquals(KeyChord.Key.K, chord.key());
    }

    @Test
    void toolResultSummaryGrep() {
        var msg = new Message.ToolResult("id", "grep", "content", false, Map.of("matchCount", 5));
        assertEquals("5 matches", ToolResultLines.toolResultSummary(msg));
    }

    @Test
    void toolResultSummaryGlob() {
        var msg = new Message.ToolResult("id", "glob", "content", false, Map.of("resultCount", 3));
        assertEquals("3 results", ToolResultLines.toolResultSummary(msg));
    }

    @Test
    void toolResultSummaryRead() {
        var msg = new Message.ToolResult("id", "read", "content", false, Map.of("totalLines", 100));
        assertEquals("100 lines", ToolResultLines.toolResultSummary(msg));
    }

    @Test
    void toolResultSummaryError() {
        var msg = new Message.ToolResult("id", "bash", "Error message\nline2", true);
        assertTrue(ToolResultLines.toolResultSummary(msg).contains("Error message"));
    }

    @Test
    void toolResultSummaryDefault() {
        var msg = new Message.ToolResult("id", "unknown", "plain content", false);
        assertEquals("plain content", ToolResultLines.toolResultSummary(msg));
    }

    @Test
    void renderToolBodyLineColoursChecklistMarkersByContent() {
        var theme = new AnsiTheme();
        assertTrue(ToolResultLines.renderToolBodyLine("│ [✓] Task done", theme).contains("✓"));
        assertTrue(ToolResultLines.renderToolBodyLine("│ [~] Working", theme).contains("~"));
        assertTrue(ToolResultLines.renderToolBodyLine("│ [−] Removed", theme).contains("−"));
        assertTrue(ToolResultLines.renderToolBodyLine("│ plain text", theme).contains("plain text"));
    }

    @Test
    void renderToolBodyLineLink() {
        var theme = new AnsiTheme();
        var result = ToolResultLines.renderToolBodyLine("╰→ result", theme);
        assertTrue(result.contains("╰→"));
    }

    @Test
    void renderToolBodyLineTreeNode() {
        var theme = new AnsiTheme();
        var result = ToolResultLines.renderToolBodyLine("┴", theme);
        assertTrue(result.contains("┴"));
    }

    @Test
    void thinkingSummaryFormatsTokens() {
        var theme = new AnsiTheme();
        var result = StreamRenderer.thinkingSummary("hello world", 0, theme);
        assertTrue(result.contains("└ "), "footer of a visible block; the header already said thinking");
        assertTrue(result.contains("tokens"));
        assertFalse(result.contains("thought"), "no word the header already carried");
    }

    @Test
    void collapsedThinkingSummaryExplainsTheHiddenPause() {
        var theme = new AnsiTheme();
        var result = StreamRenderer.collapsedThinkingSummary("hello world", 0, theme);
        assertTrue(result.contains("∴ thought"), result);
        assertTrue(result.contains("tokens"), result);
    }

    @Test
    void pendingRegionEmptyWhenNothingQueued() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of());
        assertTrue(new AppendOnlyTui.PendingRegion().compose(80, AnsiTheme.dark(), ctx).rows().isEmpty());
    }

    @Test
    void pendingRegionOneClampedRowPerQueuedMessage() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of())
                                        .withPendingMessages(List.of("short", "very long message that wraps"));
        var rows = new AppendOnlyTui.PendingRegion().compose(15, AnsiTheme.dark(), ctx).rows();
        assertEquals(2, rows.size(), "exactly one row per queued message, never a wrapped extra row");
        for (String row : rows)
            assertTrue(AnsiWidth.visibleWidth(row) <= 15, "queued row exceeds width: " + row);
    }

    @Test
    void pendingRegionSingleMessage() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of()).withPendingMessages(List.of("hello"));
        var rows = new AppendOnlyTui.PendingRegion().compose(100, AnsiTheme.dark(), ctx).rows();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).contains("queued"));
    }
}
