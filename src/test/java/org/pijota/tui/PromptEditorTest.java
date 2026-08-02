package org.pijota.tui;

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
    void handleCompletionKeyNavigatesOpenList() {
        editor.insertText("/sess");
        assertTrue(editor.hasCompletions());
        assertTrue(editor.handleCompletionKey(Key.Special.DOWN), "Down consumed within completions");
        assertTrue(editor.handleCompletionKey(Key.Special.UP), "Up consumed within completions");
        assertFalse(editor.handleCompletionKey(new Key.Char('x')), "non-arrow key not consumed");
    }

    @Test
    void handleCompletionKeyIgnoredWithoutCompletions() {
        assertFalse(editor.hasCompletions());
        assertFalse(editor.handleCompletionKey(Key.Special.DOWN));
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
    void rightArrowAtEndAcceptsTheGhostSuggestion() {
        // Typing "/mod" shows a dimmed ghost completing to "/model"; → (or End) at the end accepts it (fish-style).
        editor.insertText("/mod");
        assertEquals("el", editor.suggestion(), "ghost suggests the '/model' completion");

        editor.apply(Key.Special.RIGHT);

        assertEquals("/model", editor.text());
    }

    @Test
    void endKeyAtEndAlsoAcceptsTheGhostSuggestion() {
        editor.insertText("/mod");

        editor.apply(Key.Special.END);

        assertEquals("/model", editor.text());
    }

    @Test
    void rightArrowWithoutASuggestionJustMovesTheCursor() {
        editor.insertText("abc");
        editor.apply(Key.Special.LEFT); // cursor between b and c, no suggestion

        var result = editor.apply(Key.Special.RIGHT);

        assertEquals("abc", editor.text(), "no ghost → plain cursor move, text unchanged");
        assertFalse(result.submitted().isPresent());
    }

    @Test
    void enterSubmitsCommandTokenNotTheArgSpecPlaceholder() {
        // Typing a command and pressing Enter with the completion menu open must submit the command token, not the
        // display name with placeholders, otherwise "/save" would submit "/save [path]" and treat "[path]" as an arg.
        editor.insertText("/save");

        var result = editor.apply(Key.Special.ENTER);

        assertEquals("/save", result.submitted().orElseThrow());
    }

    @Test
    void enterPreservesArgumentsTypedAfterTheCommand() {
        editor.insertText("/save out.jsonl");

        var result = editor.apply(Key.Special.ENTER);

        assertEquals("/save out.jsonl", result.submitted().orElseThrow());
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

        // Ghost text is the trailing suffix only, rendered dimmed after the typed text ("/sess" + "ions").
        assertEquals("ions", editor.suggestion());
    }

    /**
     * Shell autosuggestion works because commands repeat verbatim; an agent prompt is fresh prose almost every time, so
     * a prefix match on an earlier message is coincidence. It was also loudest when it knew least -- one keystroke
     * could fill the line with a whole stale sentence -- and → / End accept a ghost, so a reflexive edit keystroke
     * could send a message the user never wrote.
     */
    @Test
    void doesNotGhostAPreviousMessage() {
        editor.insertText("hello world");
        editor.apply(Key.Special.ENTER);
        editor.insertText("he");

        assertEquals("", editor.suggestion(), "prose from history must not be suggested inline");
    }

    /** Commands are a closed vocabulary, so completing them is a real prediction rather than a coincidence. */
    @Test
    void stillGhostsACommand() {
        editor.insertText("/se");
        assertFalse(editor.suggestion().isEmpty(), "a command prefix still completes");
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
        editor.insertText("/c");
        var result = editor.apply(Key.Special.TAB);

        assertTrue(result.changed());
        assertEquals("/co", editor.text());
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
    void reverseSearchFiltersHistoryAsYouType() {
        editor.insertText("run the tests");
        editor.apply(Key.Special.ENTER);
        editor.insertText("read the file");
        editor.apply(Key.Special.ENTER);
        editor.insertText("commit changes");
        editor.apply(Key.Special.ENTER);
        editor.setText("");

        editor.apply(Key.Special.CTRL_R);
        assertEquals(3, editor.snapshot().completions().size(), "all history shown before typing a query");

        editor.insertText("read"); // the buffer is the live query

        var visible = editor.snapshot().completions().stream().map(CompletionItem::name).toList();
        assertEquals(List.of("read the file"), visible, "reverse-search narrows to the matching entry");
        assertTrue(editor.reverseHistoryActive(), "still in reverse-search while filtering");
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

    @Test
    void undoRevertsTypedRunAndRedoReappliesIt() {
        type("abc");
        assertEquals("abc", editor.text());

        editor.apply(Key.Special.CTRL_UNDERSCORE); // undo, a coalesced typing run reverts at once
        assertEquals("", editor.text());

        editor.apply(Key.Special.ALT_UNDERSCORE); // redo
        assertEquals("abc", editor.text());
    }

    @Test
    void undoStepsAreSplitByCursorMovement() {
        type("ab");
        editor.apply(Key.Special.LEFT);
        type("c");
        assertEquals("acb", editor.text());

        editor.apply(Key.Special.CTRL_UNDERSCORE);
        assertEquals("ab", editor.text()); // only the edit made after the cursor move is undone
        editor.apply(Key.Special.CTRL_UNDERSCORE);
        assertEquals("", editor.text());
    }

    @Test
    void killedTextIsYankedBackFromTheKillRing() {
        editor.insertText("hello world");
        editor.apply(Key.Special.CTRL_W); // kills "world"
        assertEquals("hello ", editor.text());

        editor.apply(Key.Special.CTRL_Y); // yank it back
        assertEquals("hello world", editor.text());
    }

    @Test
    void yankPopCyclesToOlderKills() {
        editor.insertText("alpha");
        editor.apply(Key.Special.CTRL_U); // kills whole buffer -> ring: [alpha]
        editor.insertText("beta");
        editor.apply(Key.Special.CTRL_U); // ring: [alpha, beta]

        editor.apply(Key.Special.CTRL_Y); // yanks most recent: "beta"
        assertEquals("beta", editor.text());
        editor.apply(Key.Special.ALT_Y); // yank-pop -> previous: "alpha"
        assertEquals("alpha", editor.text());
    }

    private void type(String s) {
        for (char c : s.toCharArray())
            editor.apply(new Key.Char(c));
    }

    private void assertSnapshot(List<String> lines, int row, int col) {
        var snapshot = editor.snapshot();
        assertEquals(lines, snapshot.lines());
        assertEquals(row, snapshot.cursorRow());
        assertEquals(col, snapshot.cursorCol());
    }
    // ---- offered suggestions (an extension proposing the next message) -------------------------------------------

    @Test
    void tabAcceptsAnOfferedSuggestionOnAnEmptyPrompt() {
        editor.offer("run the tests for calc.py");
        assertEquals("run the tests for calc.py", editor.suggestion(), "shown as ghost text on the empty prompt");

        editor.apply(Key.Special.TAB);

        assertEquals("run the tests for calc.py", editor.text(), "Tab fills the buffer, ready to edit");
        assertEquals("", editor.suggestion(), "and the ghost is consumed");
    }

    /**
     * The safety rule. Enter means send; a reflex Enter must never put a machine's words in the user's mouth, not even
     * into the buffer.
     */
    @Test
    void enterNeverAcceptsAnOfferedSuggestion() {
        editor.offer("delete the database");
        editor.apply(Key.Special.ENTER);
        assertEquals("", editor.text(), "Enter must not accept a suggestion");
    }

    @Test
    void typingDiscardsTheOfferAndItDoesNotComeBack() {
        editor.offer("run the tests");
        editor.insertText("x");
        editor.apply(Key.Special.BACKSPACE);

        assertEquals("", editor.text());
        assertEquals("", editor.suggestion(), "a suggestion that reappears after a backspace argues with the user");
    }

    @Test
    void anOfferIsIgnoredWhenTheUserHasAlreadyTypedSomething() {
        editor.insertText("my own words");
        editor.offer("something else entirely");
        assertEquals("", editor.suggestion(), "never render over what the user is writing");
    }

    @Test
    void theCommandGhostStillWinsOnceSomethingIsTyped() {
        editor.offer("run the tests");
        editor.insertText("/se");
        assertFalse(editor.suggestion().isEmpty(), "the command ghost takes over");
        assertNotEquals("run the tests", editor.suggestion());
    }

    @Test
    void tabStillCompletesNormallyWhenNothingIsOffered() {
        editor.insertText("/hel");
        editor.apply(Key.Special.TAB);
        assertTrue(editor.text().startsWith("/help"), "Tab keeps its usual job: " + editor.text());
    }

    @Test
    void anAcceptedSuggestionCanBeUndone() {
        editor.offer("run the tests");
        editor.apply(Key.Special.TAB);
        editor.apply(Key.Special.CTRL_UNDERSCORE);
        assertEquals("", editor.text(), "Ctrl+_ puts the empty prompt back");
    }

}
