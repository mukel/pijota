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
}
