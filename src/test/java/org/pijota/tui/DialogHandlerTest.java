package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DialogHandlerTest {

    private static DialogHandler.Host fakeHost() {
        return new DialogHandler.Host() {
            @Override
            public void appendPlain(List<String> lines) {}

            @Override
            public String styled(String slot, String text) {
                return "[" + slot + "]" + text;
            }

            @Override
            public void setStatus(String status) {}

            @Override
            public void clearStatus() {}

            @Override
            public void redrawPrompt() {}
        };
    }

    @Test
    void confirmReturnsFuture() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.confirm("are you sure?");
        assertNotNull(future);
        assertFalse(future.isDone());
    }

    @Test
    void confirmYCompletesTrue() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.confirm("sure?");
        assertTrue(handler.handleConfirm(new Key.Char('y')));
        assertTrue(future.join());
    }

    @Test
    void confirmCapitalYCompletesTrue() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.confirm("sure?");
        assertTrue(handler.handleConfirm(new Key.Char('Y')));
        assertTrue(future.join());
    }

    @Test
    void confirmNCompletesFalse() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.confirm("sure?");
        assertTrue(handler.handleConfirm(new Key.Char('n')));
        assertFalse(future.join());
    }

    @Test
    void confirmEscapeCompletesFalse() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.confirm("sure?");
        assertTrue(handler.handleConfirm(Key.Special.ESC));
        assertFalse(future.join());
    }

    @Test
    void confirmOtherCharsReturnTrueButDontComplete() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.confirm("sure?");
        assertTrue(handler.handleConfirm(new Key.Char('x')));
        assertFalse(future.isDone());
    }

    @Test
    void handleConfirmReturnsFalseWhenNoPending() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.handleConfirm(new Key.Char('y')));
    }

    @Test
    void promptReturnsFuture() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "default");
        assertNotNull(future);
        assertFalse(future.isDone());
    }

    @Test
    void promptTextAccumulates() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "");
        handler.handlePrompt(new Key.Char('h'));
        handler.handlePrompt(new Key.Char('i'));
        var status = handler.promptStatusLine();
        assertNotNull(status);
        assertTrue(status.contains("hi"), "Status line should contain 'hi': " + status);
    }

    @Test
    void promptEnterCompletesWithText() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "");
        handler.handlePrompt(new Key.Char('h'));
        handler.handlePrompt(Key.Special.ENTER);
        assertEquals("h", future.join());
    }

    @Test
    void promptEnterWithEmptyTextUsesDefault() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "unnamed");
        handler.handlePrompt(Key.Special.ENTER);
        assertEquals("unnamed", future.join());
    }

    @Test
    void promptEscapeCompletesWithDefault() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "default");
        handler.handlePrompt(new Key.Char('t'));
        handler.handlePrompt(Key.Special.ESC);
        assertEquals("default", future.join());
    }

    @Test
    void promptCtrlCCompletesWithDefault() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "default");
        handler.handlePrompt(new Key.Char('t'));
        handler.handlePrompt(Key.Special.CTRL_C);
        assertEquals("default", future.join());
    }

    @Test
    void promptBackspaceRemovesLastChar() {
        var handler = new DialogHandler(fakeHost());
        handler.prompt("name?", "");
        handler.handlePrompt(new Key.Char('a'));
        handler.handlePrompt(new Key.Char('b'));
        handler.handlePrompt(Key.Special.BACKSPACE);
        var status = handler.promptStatusLine();
        assertNotNull(status);
        assertTrue(status.contains("a"));
        assertFalse(status.contains("ab"));
    }

    @Test
    void handlePromptReturnsFalseWhenNoPending() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.handlePrompt(Key.Special.ENTER));
    }

    @Test
    void promptStatusLineNullWhenNoPrompt() {
        var handler = new DialogHandler(fakeHost());
        assertNull(handler.promptStatusLine());
    }

    @Test
    void promptDefaultNullCoercesToEmpty() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", null);
        handler.handlePrompt(Key.Special.ENTER);
        assertEquals("", future.join());
    }

    @Test
    void questionPendingTrueWhenActive() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.questionPending());
        handler.askQuestions(List.of());
        assertTrue(handler.questionPending());
    }

    @Test
    void cancelQuestionIfIdleWhenNotBusy() {
        var handler = new DialogHandler(fakeHost());
        handler.askQuestions(List.of());
        assertTrue(handler.cancelQuestionIfIdle(false));
        assertFalse(handler.questionPending());
    }

    @Test
    void cancelQuestionIfIdleWhenBusyReturnsFalse() {
        var handler = new DialogHandler(fakeHost());
        handler.askQuestions(List.of());
        assertFalse(handler.cancelQuestionIfIdle(true));
        assertTrue(handler.questionPending());
    }

    @Test
    void cancelQuestionIfIdleWhenNoQuestionReturnsFalse() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.cancelQuestionIfIdle(false));
    }

    @Test
    void submitQuestionAnswerCompletesAndClears() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.askQuestions(List.of());
        assertTrue(handler.submitQuestionAnswer("my answer", () -> {}));
        assertEquals("my answer", future.join());
        assertFalse(handler.questionPending());
    }

    @Test
    void submitQuestionAnswerWhenNonePendingReturnsFalse() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.submitQuestionAnswer("answer", () -> {}));
    }
}
