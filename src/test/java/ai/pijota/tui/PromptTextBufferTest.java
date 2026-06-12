package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptTextBufferTest {
    @Test
    void insertsMovesAndDeletesWords() {
        var buffer = new PromptTextBuffer();

        buffer.insertText("hello world");
        buffer.left();
        buffer.left();
        buffer.insert('!');

        assertEquals("hello wor!ld", buffer.text());
        assertEquals(0, buffer.row());
        assertEquals(10, buffer.col());

        assertEquals(PromptTextBuffer.Effect.REFRESH_COMPLETIONS, buffer.deleteWordBeforeCursor());

        assertEquals("hello ld", buffer.text());
        assertEquals(6, buffer.col());
    }

    @Test
    void supportsMultilineEditing() {
        var buffer = new PromptTextBuffer();

        buffer.insertText("first");
        buffer.newline();
        buffer.insertText("second");
        buffer.up();
        buffer.end();
        buffer.insertText(" line");

        assertEquals(List.of("first line", "second"), buffer.lines());
        assertEquals("first line\nsecond", buffer.text());
        assertEquals(0, buffer.row());
        assertEquals(10, buffer.col());
    }

    @Test
    void restoresTrailingBlankLineWhenSettingText() {
        var buffer = new PromptTextBuffer();

        buffer.setText("first\n");

        assertEquals(List.of("first", ""), buffer.lines());
        assertEquals(1, buffer.row());
        assertEquals(0, buffer.col());
    }

    @Test
    void emptyBufferQueries() {
        var buffer = new PromptTextBuffer();
        assertTrue(buffer.isEmpty());
        assertTrue(buffer.isAtEndOfFirstLine());
        assertTrue(buffer.cursorAtEndOfBuffer());
        assertEquals("", buffer.firstLine());
        assertTrue(buffer.isSingleLineStartingWith(""));
    }

    @Test
    void singleCharInsert() {
        var buffer = new PromptTextBuffer();
        assertEquals(PromptTextBuffer.Effect.REFRESH_COMPLETIONS, buffer.insert('x'));
        assertEquals("x", buffer.text());
        assertEquals(0, buffer.row());
        assertEquals(1, buffer.col());
    }

    @Test
    void insertTextIgnoresNullAndEmpty() {
        var buffer = new PromptTextBuffer();
        assertEquals(PromptTextBuffer.Effect.UNCHANGED, buffer.insertText(null));
        assertEquals(PromptTextBuffer.Effect.UNCHANGED, buffer.insertText(""));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void insertTextMultilinePreservesSuffix() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("ab");
        buffer.left();
        var effect = buffer.insertText("x\ny");
        assertEquals(PromptTextBuffer.Effect.REFRESH_COMPLETIONS, effect);
        assertEquals(List.of("ax", "yb"), buffer.lines());
        assertEquals(1, buffer.row());
        assertEquals(1, buffer.col());
    }

    @Test
    void newlineAndBackspaceMergeLines() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("ab");
        buffer.newline();
        buffer.insertText("cd");
        assertEquals(List.of("ab", "cd"), buffer.lines());
        buffer.left();
        buffer.left();
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.backspace());
        assertEquals(List.of("abcd"), buffer.lines());
        assertEquals(2, buffer.col());
    }

    @Test
    void backspaceAtTopLeftIsUnchanged() {
        var buffer = new PromptTextBuffer();
        assertEquals(PromptTextBuffer.Effect.UNCHANGED, buffer.backspace());
    }

    @Test
    void deleteMergesAndAtBufferEnd() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("ab");
        buffer.newline();
        buffer.insertText("cd");
        buffer.up();
        buffer.end();
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.delete());
        assertEquals(List.of("abcd"), buffer.lines());
        assertEquals(PromptTextBuffer.Effect.UNCHANGED, buffer.delete());
    }

    @Test
    void leftRightMoveAcrossLines() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("ab");
        buffer.newline();
        buffer.insertText("c");
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.left());
        assertEquals(0, buffer.col());
        buffer.left();
        assertEquals(2, buffer.col());
        assertEquals(0, buffer.row());
        buffer.right();
        buffer.right();
        assertEquals(0, buffer.col());
        assertEquals(1, buffer.row());
        assertEquals(PromptTextBuffer.Effect.UNCHANGED, buffer.right());
    }

    @Test
    void upDownDesiredColumn() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("abc");
        buffer.newline();
        buffer.insertText("d");
        buffer.up();
        assertEquals(1, buffer.col());
        buffer.end();
        buffer.down();
        assertEquals(1, buffer.col());
    }

    @Test
    void homeEndKillToEnd() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("hello");
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.home());
        assertEquals(0, buffer.col());
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.end());
        assertEquals(5, buffer.col());
        buffer.left();
        buffer.left();
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.killToEnd());
        assertEquals("hel", buffer.text());
    }

    @Test
    void deleteWordBeforeAndAfterCursor() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("one two three");
        buffer.end();
        assertEquals(PromptTextBuffer.Effect.REFRESH_COMPLETIONS, buffer.deleteWordBeforeCursor());
        assertEquals("one two", buffer.text());
        buffer.home();
        assertEquals(PromptTextBuffer.Effect.REFRESH_COMPLETIONS, buffer.deleteWordAfterCursor());
        assertEquals(" two", buffer.text());
    }

    @Test
    void wordLeftAndRight() {
        var buffer = new PromptTextBuffer();
        buffer.insertText("hello world");
        buffer.end();
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.wordLeft());
        assertEquals(6, buffer.col());
        assertEquals(PromptTextBuffer.Effect.CLEAR_COMPLETIONS, buffer.wordRight());
        assertEquals(11, buffer.col());
    }

    @Test
    void setTextResetAndReplace() {
        var buffer = new PromptTextBuffer();
        buffer.setText("a\nb\n");
        assertEquals(List.of("a", "b", ""), buffer.lines());
        assertEquals(2, buffer.row());
        assertEquals(0, buffer.col());
        buffer.setText(null);
        assertTrue(buffer.isEmpty());
        buffer.setText("");
        assertTrue(buffer.isEmpty());
        buffer.replaceSingleLine("xyz");
        assertEquals("xyz", buffer.text());
        assertEquals(3, buffer.col());
        buffer.replaceSingleLine(null);
        assertEquals("", buffer.text());
        buffer.reset();
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.row());
        assertEquals(0, buffer.col());
    }
}
