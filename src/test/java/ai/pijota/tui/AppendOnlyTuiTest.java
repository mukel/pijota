package ai.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppendOnlyTuiTest {

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
        var result = AppendOnlyTui.compactModelName("openai", "gpt-4o");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void compactModelNameLongProvider() {
        var result = AppendOnlyTui.compactModelName("anthropic", "claude-sonnet-4-5-20250929");
        assertNotNull(result);
    }

    @Test
    void isEditWriteForEdit() {
        assertTrue(AppendOnlyTui.isEditWrite(
                                        new ai.pijota.messages.Message.ToolResult("id", "edit", "content", false)));
    }

    @Test
    void isEditWriteForWrite() {
        assertTrue(AppendOnlyTui.isEditWrite(
                                        new ai.pijota.messages.Message.ToolResult("id", "write", "content", false)));
    }

    @Test
    void isEditWriteForRead() {
        assertFalse(AppendOnlyTui.isEditWrite(
                                        new ai.pijota.messages.Message.ToolResult("id", "read", "content", false)));
    }

    @Test
    void isEditWriteForBash() {
        assertFalse(AppendOnlyTui.isEditWrite(
                                        new ai.pijota.messages.Message.ToolResult("id", "bash", "content", false)));
    }

    @Test
    void toolIconReturnsNonEmptyForAllKnownTools() {
        var tools = new String[] {"read", "write", "edit", "bash", "grep", "glob", "ls", "task", "question",
                "websearch", "webfetch", "todowrite"};
        for (var t : tools) {
            assertFalse(AppendOnlyTui.toolIcon(t, false).isEmpty(), "tool " + t + " should have icon");
        }
    }

    @Test
    void toolIconErrorReturnsX() {
        assertEquals("✖", AppendOnlyTui.toolIcon("bash", true));
    }

    @Test
    void toolIconUnknownReturnsDot() {
        assertEquals("●", AppendOnlyTui.toolIcon("unknown-tool", false));
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
        assertTrue(AppendOnlyTui.insidePromptCodeBlock(List.of("text", "```java", "code")));
    }

    @Test
    void insidePromptCodeBlockWithClosedFence() {
        assertFalse(AppendOnlyTui.insidePromptCodeBlock(List.of("text", "```java", "code", "```", "more")));
    }

    @Test
    void insidePromptCodeBlockWithoutFence() {
        assertFalse(AppendOnlyTui.insidePromptCodeBlock(List.of("text", "no code block")));
    }

    @Test
    void insidePromptCodeBlockWithTildeFence() {
        assertTrue(AppendOnlyTui.insidePromptCodeBlock(List.of("~~~bash", "cmd")));
        assertFalse(AppendOnlyTui.insidePromptCodeBlock(List.of("~~~bash", "cmd", "~~~")));
    }

    @Test
    void showExtensionStatusLineNormal() {
        assertTrue(AppendOnlyTui.showExtensionStatusLine("hello"));
    }

    @Test
    void showExtensionStatusLineFiltered() {
        assertFalse(AppendOnlyTui.showExtensionStatusLine("something filtered"));
    }

    @Test
    void showExtensionStatusLineSecured() {
        assertFalse(AppendOnlyTui.showExtensionStatusLine("secured status"));
    }

    @Test
    void showExtensionStatusLineEmpty() {
        assertTrue(AppendOnlyTui.showExtensionStatusLine(""));
    }

    @Test
    void showExtensionStatusLineNull() {
        assertFalse(AppendOnlyTui.showExtensionStatusLine(null));
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
        assertEquals(ai.pijota.extensions.KeyChord.Key.ENTER, chord.key());
    }

    @Test
    void toKeyChordEsc() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.ESC);
        assertEquals(ai.pijota.extensions.KeyChord.Key.ESC, chord.key());
    }

    @Test
    void toKeyChordChar() {
        assertNull(AppendOnlyTui.toKeyChord(new Key.Char('a')));
    }

    @Test
    void toKeyChordCtrlC() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.CTRL_C);
        assertTrue(chord.modifiers().contains(ai.pijota.extensions.KeyChord.Modifier.CTRL));
        assertEquals(ai.pijota.extensions.KeyChord.Key.C, chord.key());
    }

    @Test
    void toKeyChordCtrlK() {
        var chord = AppendOnlyTui.toKeyChord(Key.Special.CTRL_K);
        assertTrue(chord.modifiers().contains(ai.pijota.extensions.KeyChord.Modifier.CTRL));
        assertEquals(ai.pijota.extensions.KeyChord.Key.K, chord.key());
    }
}
