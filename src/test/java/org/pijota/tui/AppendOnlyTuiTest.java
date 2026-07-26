package org.pijota.tui;

import org.pijota.theme.AnsiTheme;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppendOnlyTuiTest {

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
        assertTrue(AppendOnlyTui.isEditWrite(
                                        new org.pijota.messages.Message.ToolResult("id", "edit", "content", false)));
    }

    @Test
    void isEditWriteForWrite() {
        assertTrue(AppendOnlyTui.isEditWrite(
                                        new org.pijota.messages.Message.ToolResult("id", "write", "content", false)));
    }

    @Test
    void isEditWriteForRead() {
        assertFalse(AppendOnlyTui.isEditWrite(
                                        new org.pijota.messages.Message.ToolResult("id", "read", "content", false)));
    }

    @Test
    void isEditWriteForBash() {
        assertFalse(AppendOnlyTui.isEditWrite(
                                        new org.pijota.messages.Message.ToolResult("id", "bash", "content", false)));
    }

    @Test
    void toolsProvideTheirOwnGlyph() {
        assertEquals("📖", new org.pijota.tools.ReadTool().glyph());
        assertEquals("🔍", new org.pijota.tools.GrepTool().glyph());
        assertEquals("●", new org.pijota.tools.WhoamiTool().glyph(), "tools without a custom glyph use the bullet");
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
    void bareHelpInputsOpenHelpInsteadOfPromptingModel() {
        assertTrue(AppendOnlyTui.isBareHelp("help"));
        assertFalse(AppendOnlyTui.isBareHelp("help remote"));
        assertFalse(AppendOnlyTui.isBareHelp("helpful"));
        assertFalse(AppendOnlyTui.isBareHelp("please help"));
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
        assertEquals(org.pijota.extensions.KeyChord.Key.ENTER, chord.key());
    }

    @Test
    void toKeyChordEsc() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.ESC);
        assertEquals(org.pijota.extensions.KeyChord.Key.ESC, chord.key());
    }

    @Test
    void toKeyChordChar() {
        assertNull(AppendOnlyTui.toKeyChord(new Key.Char('a')));
    }

    @Test
    void toKeyChordCtrlC() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.CTRL_C);
        assertTrue(chord.modifiers().contains(org.pijota.extensions.KeyChord.Modifier.CTRL));
        assertEquals(org.pijota.extensions.KeyChord.Key.C, chord.key());
    }

    @Test
    void toKeyChordCtrlK() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.CTRL_K);
        assertTrue(chord.modifiers().contains(org.pijota.extensions.KeyChord.Modifier.CTRL));
        assertEquals(org.pijota.extensions.KeyChord.Key.K, chord.key());
    }

    @Test
    void toolResultSummaryGrep() {
        var msg = new org.pijota.messages.Message.ToolResult("id", "grep", "content", false, Map.of("matchCount", 5));
        assertEquals("5 match(es)", ToolResultLines.toolResultSummary(msg));
    }

    @Test
    void toolResultSummaryGlob() {
        var msg = new org.pijota.messages.Message.ToolResult("id", "glob", "content", false, Map.of("resultCount", 3));
        assertEquals("3 result(s)", ToolResultLines.toolResultSummary(msg));
    }

    @Test
    void toolResultSummaryRead() {
        var msg = new org.pijota.messages.Message.ToolResult("id", "read", "content", false, Map.of("totalLines", 100));
        assertEquals("100 line(s)", ToolResultLines.toolResultSummary(msg));
    }

    @Test
    void toolResultSummaryError() {
        var msg = new org.pijota.messages.Message.ToolResult("id", "bash", "Error message\nline2", true);
        assertTrue(ToolResultLines.toolResultSummary(msg).contains("Error message"));
    }

    @Test
    void toolResultSummaryDefault() {
        var msg = new org.pijota.messages.Message.ToolResult("id", "unknown", "plain content", false);
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
        assertTrue(result.contains("thought"));
        assertTrue(result.contains("tokens"));
    }

    @Test
    void pendingHeightZeroWhenEmpty() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of());
        assertEquals(0, AppendOnlyTui.pendingHeight(80, ctx));
    }

    @Test
    void pendingHeightCountsWrappedLines() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of())
                                        .withPendingMessages(List.of("short", "very long message that wraps"));
        int h = AppendOnlyTui.pendingHeight(15, ctx);
        assertTrue(h > 2);
        assertTrue(h <= 10);
    }

    @Test
    void pendingHeightSingleMessage() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of()).withPendingMessages(List.of("hello"));
        int h = AppendOnlyTui.pendingHeight(100, ctx);
        assertEquals(1, h);
    }
}
