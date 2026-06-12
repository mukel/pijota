package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptEditorTest {
    private final PromptEditor editor = new PromptEditor(new SlashCommandRegistry());

    @Test
    void typesMovesAndEditsSingleLineText() {
        editor.insertText("hello world");

        editor.apply(Key.Special.LEFT);
        editor.apply(Key.Special.LEFT);
        editor.apply(new Key.Char('!'));

        assertEquals("hello wor!ld", editor.text());
        assertSnapshot(List.of("hello wor!ld"), 0, 10);

        editor.apply(Key.Special.CTRL_W);

        assertEquals("hello ld", editor.text());
        assertSnapshot(List.of("hello ld"), 0, 6);
    }

    @Test
    void supportsMultilineEditingAndSubmission() {
        editor.insertText("first");
        editor.apply(Key.Special.NEWLINE);
        editor.insertText("second");
        editor.apply(Key.Special.UP);
        editor.apply(Key.Special.END);
        editor.insertText(" line");

        assertEquals("first line\nsecond", editor.text());
        assertSnapshot(List.of("first line", "second"), 0, 10);

        var result = editor.apply(Key.Special.ENTER);

        assertTrue(result.submitted().isPresent());
        assertEquals("first line\nsecond", result.submitted().orElseThrow());
        assertTrue(editor.isEmpty());
    }

    @Test
    void blankInputDoesNotSubmit() {
        var result = editor.apply(Key.Special.ENTER);

        assertTrue(result.submitted().isEmpty());
        assertTrue(result.changed());
        assertTrue(editor.isEmpty());
    }

    @Test
    void storesSubmittedHistoryAndRestoresDraft() {
        editor.insertText("first prompt");
        editor.apply(Key.Special.ENTER);
        editor.insertText("second prompt");
        editor.apply(Key.Special.ENTER);
        editor.insertText("draft");

        assertTrue(editor.historyPrev());
        assertEquals("second prompt", editor.text());
        assertTrue(editor.historyPrev());
        assertEquals("first prompt", editor.text());

        editor.historyNext();
        assertEquals("second prompt", editor.text());
        editor.historyNext();
        assertEquals("draft", editor.text());
    }

    @Test
    void exposesSlashCommandCompletionsAndSubmitsExactCommand() {
        editor.insertText("/sess");

        assertTrue(editor.hasCompletions());
        var snapshot = editor.snapshot();
        assertTrue(snapshot.completions().stream().anyMatch(c -> c.name().equals("/sessions")));

        editor.setText("/sessions");
        var result = editor.apply(Key.Special.ENTER);

        assertTrue(result.submitted().isPresent());
        assertEquals("/sessions", result.submitted().orElseThrow());
        assertTrue(editor.isEmpty());
    }

    @Test
    void usesInjectedFileCompletionProvider() {
        var editor = new PromptEditor(new SlashCommandRegistry(),
                                        text -> List.of(new CompletionItem(text + ".java", "file")));

        editor.insertText("src/Main");
        var result = editor.apply(Key.Special.TAB);

        assertTrue(result.submitted().isEmpty());
        assertTrue(editor.hasCompletions());

        result = editor.apply(Key.Special.ENTER);

        assertTrue(result.submitted().isEmpty());
        assertEquals("src/Main.java", editor.text());
        assertFalse(editor.hasCompletions());
    }

    @Test
    void suggestsSlashCommandCompletion() {
        editor.insertText("/sess");

        assertEquals("/sessions", editor.suggestion());
    }

    @Test
    void suggestsFromHistory() {
        editor.insertText("hello world");
        editor.apply(Key.Special.ENTER);
        editor.insertText("he");

        assertEquals("hello world", editor.suggestion());
    }

    @Test
    void clearsSuggestionWhenCursorMoves() {
        editor.insertText("/se");
        assertFalse(editor.suggestion().isEmpty());

        editor.apply(Key.Special.LEFT);
        assertTrue(editor.suggestion().isEmpty());
    }

    @Test
    void tabInsertsCommonPrefixForMultipleSlashCommands() {
        editor.insertText("/ses");
        var result = editor.apply(Key.Special.TAB);

        assertTrue(result.changed());
        assertEquals("/session", editor.text());
    }

    @Test
    void ctrlRActivatesReverseHistorySearch() {
        editor.insertText("first");
        editor.apply(Key.Special.ENTER);
        editor.insertText("second");
        editor.apply(Key.Special.ENTER);
        editor.setText("");

        var result = editor.apply(Key.Special.CTRL_R);

        assertTrue(result.changed());
        assertTrue(editor.reverseHistoryActive());
        assertTrue(editor.hasCompletions());
    }

    @Test
    void selectingReverseHistoryItemInsertsIt() {
        editor.insertText("first");
        editor.apply(Key.Special.ENTER);
        editor.insertText("second");
        editor.apply(Key.Special.ENTER);
        editor.setText("");

        editor.apply(Key.Special.CTRL_R);
        var result = editor.apply(Key.Special.ENTER);

        assertTrue(result.changed());
        assertEquals("second", editor.text());
        assertFalse(editor.reverseHistoryActive());
    }

    private void assertSnapshot(List<String> lines, int row, int col) {
        var snapshot = editor.snapshot();
        assertEquals(lines, snapshot.lines());
        assertEquals(row, snapshot.cursorRow());
        assertEquals(col, snapshot.cursorCol());
    }
}
