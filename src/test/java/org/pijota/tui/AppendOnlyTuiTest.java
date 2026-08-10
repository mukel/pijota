package org.pijota.tui;

import org.pijota.extensions.KeyChord;
import org.pijota.llm.ModelRegistry;
import org.pijota.messages.Message;
import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;
import org.pijota.tools.EditTool;
import org.pijota.tools.GrepTool;
import org.pijota.tools.ReadTool;
import org.pijota.tools.ToolGlyphs;
import org.pijota.tools.WhoamiTool;
import org.pijota.tools.WriteTool;
import org.pijota.util.ParentWatch;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Ctrl+Z has to stop the supervisor too. Under it the shell is waiting on the supervisor, not on us, so stopping
     * only ourselves left the shell with nothing to report and nothing to fg: a frozen screen, keystrokes echoed raw
     * over it by a tty nobody was reading, and Ctrl+C the only way out -- which kills the session instead of suspending
     * it.
     */
    @Test
    void ctrlZStopsTheSupervisorBeforeItself() {
        assertEquals(List.of("kill", "-TSTP", "111", "222"), JobControl.suspendCommand(Optional.of(111L), 222L));
    }

    /**
     * Unsupervised -- a native launch -- the parent is the user's shell, and stopping that would be far worse than the
     * bug this guards. Empty must mean "only me".
     */
    @Test
    void ctrlZStopsOnlyItselfWhenNothingIsSupervisingIt() {
        assertEquals(List.of("kill", "-TSTP", "222"), JobControl.suspendCommand(Optional.empty(), 222L));
        // And that is what the real lookup answers in any process no supervisor started, this test JVM included.
        assertTrue(ParentWatch.supervisor().isEmpty(), "nothing supervises a test run");
    }

    /**
     * The label has to be the identifier. It used to strip a redundant-looking provider prefix from the model id, so
     * deepseek-v4-flash was shown as {@code deepseek/v4-flash} -- and passing that back to {@code --model} is answered
     * with "the supported API model names are deepseek-v4-pro or deepseek-v4-flash, but you passed v4-flash". In a
     * coding agent a model name gets copied into a flag, a settings file, an alias target or a /model search, so a
     * prettier version of it is a trap. The old tests only asserted the result was non-blank, which is why the
     * shortening went unquestioned.
     */
    @Test
    void aModelLabelIsSomethingYouCanPasteBackIntoTheModelFlag() {
        assertEquals("deepseek/deepseek-v4-flash", PromptPresenter.modelLabel("deepseek", "deepseek-v4-flash"));
        assertEquals("openai/gpt-4o", PromptPresenter.modelLabel("openai", "gpt-4o"));
        assertEquals("anthropic/claude-sonnet-4-5-20250929",
                                        PromptPresenter.modelLabel("anthropic", "claude-sonnet-4-5-20250929"));

        // What the flag parser does with it must give back exactly what was displayed.
        for (String[] pair : new String[][] {{"deepseek", "deepseek-v4-flash"}, {"openai", "gpt-4o"}}) {
            var parsed = ModelRegistry.parseProviderModel(PromptPresenter.modelLabel(pair[0], pair[1]));
            assertEquals(pair[0], parsed.provider(), "provider survives the round trip");
            assertEquals(pair[1], parsed.model(), "and so does the model id, verbatim");
        }
    }

    @Test
    void aModelLabelWithHalfTheInformationIsStillReadable() {
        assertEquals("gpt-4o", PromptPresenter.modelLabel(null, "gpt-4o"));
        assertEquals("openai", PromptPresenter.modelLabel("openai", " "));
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
    void theGlyphSaysWhatTheCallDidNotWhichToolRan() {
        // The tool's name is printed right after the mark, so the mark carries the effect instead: hollow looked,
        // filled wrote. That is the distinction worth spotting when reviewing what an agent did to a checkout.
        assertEquals(ToolGlyphs.INSPECT, new ReadTool().glyph());
        assertEquals(ToolGlyphs.INSPECT, new GrepTool().glyph());
        assertEquals(ToolGlyphs.MUTATE, new WriteTool().glyph());
        assertEquals(ToolGlyphs.MUTATE, new EditTool().glyph());
        assertNotEquals(ToolGlyphs.MUTATE, new WhoamiTool().glyph(),
                                        "a tool that declares no glyph must not be shown as having written to disk");
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
    void anExtensionsStatusLineIsShownWhateverItSays() {
        // These two words used to be a denylist here, so an extension whose chip said "filtered" or "secured" was
        // silently dropped -- which is how the two safety toggles came to be invisible in exactly their on state.
        // Which state deserves a chip is the extension's call; this merge only de-duplicates and flattens.
        var merged = PromptPresenter.mergeExtensionLines(
                                        List.of("hello", "something filtered", "secured status", "hello"));
        assertEquals(List.of("hello", "something filtered", "secured status"), merged);
    }

    @Test
    void mergeExtensionLinesDropsNothingButBlanksAndNulls() {
        var merged = PromptPresenter.mergeExtensionLines(Arrays.asList("a", null, "", "   ", "b"));
        assertEquals(List.of("a", "b"), merged);
    }

    @Test
    void mergeExtensionLinesFlattensNewlinesSoOneChipCannotBecomeTwoRows() {
        assertEquals(List.of("a b"), PromptPresenter.mergeExtensionLines(List.of("a\nb")));
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
        assertTrue(KeyMaps.isReservedKey(Key.Special.ENTER));
    }

    @Test
    void isReservedKeyEsc() {
        assertTrue(KeyMaps.isReservedKey(Key.Special.ESC));
    }

    @Test
    void isReservedKeyChar() {
        assertFalse(KeyMaps.isReservedKey(new Key.Char('a')));
    }

    @Test
    void toKeyChordEnter() {
        var chord = KeyMaps.toKeyChord(Key.Special.ENTER);
        assertEquals(KeyChord.Key.ENTER, chord.key());
    }

    @Test
    void toKeyChordEsc() {
        var chord = KeyMaps.toKeyChord(Key.Special.ESC);
        assertEquals(KeyChord.Key.ESC, chord.key());
    }

    @Test
    void toKeyChordChar() {
        assertNull(KeyMaps.toKeyChord(new Key.Char('a')));
    }

    @Test
    void toKeyChordCtrlC() {
        var chord = KeyMaps.toKeyChord(Key.Special.CTRL_C);
        assertTrue(chord.modifiers().contains(KeyChord.Modifier.CTRL));
        assertEquals(KeyChord.Key.C, chord.key());
    }

    @Test
    void toKeyChordCtrlK() {
        var chord = KeyMaps.toKeyChord(Key.Special.CTRL_K);
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
        assertTrue(new AppendOnlyTui.PendingRegion(() -> false).compose(80, AnsiTheme.dark(), ctx).rows().isEmpty());
    }

    @Test
    void pendingRegionOneClampedRowPerQueuedMessage() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of())
                                        .withPendingMessages(List.of("short", "very long message that wraps"));
        var rows = new AppendOnlyTui.PendingRegion(() -> false).compose(15, AnsiTheme.dark(), ctx).rows();
        assertEquals(2, rows.size(), "exactly one row per queued message, never a wrapped extra row");
        for (String row : rows)
            assertTrue(AnsiWidth.visibleWidth(row) <= 15, "queued row exceeds width: " + row);
    }

    /**
     * A loop whose re-prompts outran its turns queued two dozen, and the region drew every one of them: the
     * conversation went off the top of the screen behind copies of a single sentence. The count on the stats line stays
     * exact; what is capped is how much of the queue gets spelled out.
     */
    @Test
    void pendingRegionCapsALongQueueAndSaysHowMuchItHid() {
        var many = new java.util.ArrayList<String>();
        for (int i = 0; i < 24; i++)
            many.add("Keep working toward the objective");
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of()).withPendingMessages(many);

        var rows = new AppendOnlyTui.PendingRegion(() -> false).compose(100, AnsiTheme.dark(), ctx).rows();
        assertEquals(4, rows.size(), "three queued rows plus the affordance, not 24: " + rows.size());
        assertTrue(AnsiStripper.strip(rows.get(3)).contains("21 more"), rows.get(3));
        // The affordance is not itself a queued message, so it does not wear the label.
        assertFalse(AnsiStripper.strip(rows.get(3)).contains("queued"), rows.get(3));

        // Ctrl+O unrolls it, the same key that unrolls the status rows.
        var expanded = new AppendOnlyTui.PendingRegion(() -> true).compose(100, AnsiTheme.dark(), ctx).rows();
        assertEquals(25, expanded.size(), "all 24 plus the collapse affordance");
    }

    @Test
    void pendingRegionSingleMessage() {
        var ctx = new PromptSnapshot(List.of(), 0, 0, List.of()).withPendingMessages(List.of("hello"));
        var rows = new AppendOnlyTui.PendingRegion(() -> false).compose(100, AnsiTheme.dark(), ctx).rows();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).contains("queued"));
    }
}
