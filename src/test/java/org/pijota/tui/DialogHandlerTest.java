package org.pijota.tui;

import org.pijota.tools.SlashCommandTool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DialogHandlerTest {

    private static DialogHandler.Host fakeHost() {
        return fakeHost(new java.util.ArrayList<>());
    }

    private static DialogHandler.Host fakeHost(List<String> transcript) {
        return new DialogHandler.Host() {
            @Override
            public void appendPlain(List<String> lines) {
                transcript.addAll(lines);
            }

            @Override
            public String styled(String slot, String text) {
                return "[" + slot + "]" + text;
            }

            @Override
            public void clearStatus() {}

            @Override
            public void redrawPrompt() {}
        };
    }

    private static SlashCommandTool.Question oneQuestion() {
        return new SlashCommandTool.Question("pick?", "Pick", List.of(), false);
    }

    /**
     * A dialog settles its future on the common pool, so an answer is waited for. Peeking with getNow passes or fails
     * depending on which thread wins, which is a test that reports the scheduler rather than the code.
     */
    private static String settled(java.util.concurrent.Future<String> future) throws Exception {
        return future.get(2, java.util.concurrent.TimeUnit.SECONDS);
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
    void promptEnterCompletesWithText() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "");
        assertFalse(handler.handlePrompt(new Key.Char('h')), "typing is the editor's, not the dialog's");
        assertTrue(handler.submitPromptAnswer("h", () -> {}));
        assertEquals("h", future.join());
    }

    @Test
    void promptEnterWithEmptyTextUsesDefault() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "unnamed");
        assertTrue(handler.submitPromptAnswer("", () -> {}));
        assertEquals("unnamed", future.join());
    }

    @Test
    void promptEscapeCompletesWithDefault() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "default");
        handler.handlePrompt(Key.Special.ESC);
        assertEquals("default", future.join());
    }

    @Test
    void promptCtrlCCompletesWithDefault() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "default");
        handler.handlePrompt(Key.Special.CTRL_C);
        assertEquals("default", future.join());
    }

    @Test
    void handlePromptReturnsFalseWhenNoPending() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.handlePrompt(Key.Special.ENTER));
    }

    @Test
    void promptDefaultNullCoercesToEmpty() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", null);
        assertTrue(handler.submitPromptAnswer("   ", () -> {}));
        assertEquals("", future.join());
    }

    @Test
    void questionPendingTrueWhenActive() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.anyPending());
        handler.askQuestions(List.of(new SlashCommandTool.Question("pick?", "Pick", List.of(), false)));
        assertTrue(handler.anyPending());
    }

    @Test
    void submitQuestionAnswerCompletesAndClears() {
        var handler = new DialogHandler(fakeHost());
        var future = handler.askQuestions(List.of(oneQuestion()));
        assertTrue(handler.submitQuestionAnswer("my answer", () -> {}));
        assertEquals("my answer", future.join());
        assertFalse(handler.anyPending());
    }

    @Test
    void submitQuestionAnswerWhenNonePendingReturnsFalse() {
        var handler = new DialogHandler(fakeHost());
        assertFalse(handler.submitQuestionAnswer("answer", () -> {}));
    }

    /**
     * Asked once. The keys first travelled through the status override, which renders in the warning slot beneath the
     * stats row: measured live, that put "y to confirm, n to cancel" eleven rows below its own question, dim yellow,
     * reading as a stray warning. Moving them onto the question fixed the distance and then the caret grew an
     * affordance of its own, so the same question carried "[y/n]" and "y / n" one row apart and read as two.
     */
    @Test
    void aConfirmAsksOnceAndTheKeysLiveAtTheCaret() {
        var transcript = new java.util.ArrayList<String>();
        var handler = new DialogHandler(fakeHost(transcript));

        handler.confirm("delete session 'x'?");

        assertEquals("delete session 'x'?", transcript.get(transcript.size() - 1), "the question, and only it");
        assertEquals("y / n", handler.caretAffordance(), "the keys are one row below, at the caret");
        assertTrue(transcript.stream().noneMatch(l -> l.contains("y to confirm")),
                                        "no third copy adrift in the status slot: " + transcript);
    }

    /**
     * The answer is the editor's text now. It used to be a second editor inside the dialog -- Char appended to a
     * StringBuilder, Backspace trimmed it, and the result was echoed into the status bar under a highlighted space
     * standing in for a cursor -- so two things on screen looked like input and the live one was not the box.
     */
    @Test
    void aPromptIsAnsweredWithTheEditorsText() throws Exception {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "");

        assertFalse(handler.handlePrompt(new Key.Char('h')), "text is the editor's, not the dialog's");
        assertTrue(handler.submitPromptAnswer("hi", () -> {}));
        assertEquals("hi", settled(future));
    }

    /** A blank answer takes the default, which is what "just press Enter" has to mean. */
    @Test
    void anEmptyPromptAnswerTakesTheDefault() throws Exception {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "fallback");

        assertTrue(handler.submitPromptAnswer("   ", () -> {}));
        assertEquals("fallback", settled(future));
    }

    /** Esc still belongs to the dialog: it cancels rather than reaching the editor. */
    @Test
    void escCancelsAPromptWithItsDefault() throws Exception {
        var handler = new DialogHandler(fakeHost());
        var future = handler.prompt("name?", "fallback");

        assertTrue(handler.handlePrompt(Key.Special.ESC));
        assertEquals("fallback", settled(future));
    }

    /** Each dialog says at the caret what will answer it, where the eye already is. */
    @Test
    void everyPendingDialogOffersAnAffordanceAtTheCaret() {
        var confirm = new DialogHandler(fakeHost());
        assertNull(confirm.caretAffordance(), "nothing pending, nothing to say");
        confirm.confirm("sure?");
        assertEquals("y / n", confirm.caretAffordance());

        var prompt = new DialogHandler(fakeHost());
        prompt.prompt("name?", "fallback");
        assertTrue(prompt.caretAffordance().contains("fallback"),
                                        "the default is worth naming: " + prompt.caretAffordance());
    }

    /**
     * Several questions used to be printed together and answered with one line, each restarting its options at 1) -- so
     * "2" could not say which question it answered, and nothing on screen said which was being asked. One at a time,
     * and the answers come back labelled so the model can tell them apart.
     */
    @Test
    void severalQuestionsAreAskedOneAtATime() throws Exception {
        var transcript = new java.util.ArrayList<String>();
        var handler = new DialogHandler(fakeHost(transcript));
        var opts = List.of(new SlashCommandTool.Option("alpha", ""), new SlashCommandTool.Option("beta", ""));
        var future = handler.askQuestions(List.of(new SlashCommandTool.Question("first?", "One", opts, false),
                                        new SlashCommandTool.Question("second?", "Two", opts, false)));

        assertTrue(transcript.stream().anyMatch(l -> l.contains("first?")), "the first is asked: " + transcript);
        assertTrue(transcript.stream().noneMatch(l -> l.contains("second?")),
                                        "and the second waits its turn: " + transcript);
        assertTrue(transcript.stream().anyMatch(l -> l.contains("1 of 2")), "you are told where you are");
        assertFalse(future.isDone(), "nothing is answered yet");

        handler.submitQuestionAnswer("1", () -> {});
        assertTrue(transcript.stream().anyMatch(l -> l.contains("second?")), "then the second is asked");
        assertFalse(future.isDone(), "still not done with one answer in");

        handler.submitQuestionAnswer("2", () -> {});
        assertEquals("One: 1\nTwo: 2", settled(future), "answers come back labelled");
    }

    /** A single question keeps its plain shape: no "1 of 1", no label on the answer. */
    @Test
    void aLoneQuestionIsNotDressedUpAsASet() throws Exception {
        var transcript = new java.util.ArrayList<String>();
        var handler = new DialogHandler(fakeHost(transcript));
        var future = handler.askQuestions(List.of(new SlashCommandTool.Question("only?", "Only", List.of(), false)));

        assertTrue(transcript.stream().noneMatch(l -> l.contains("1 of 1")), "no progress on a set of one");
        handler.submitQuestionAnswer("yes", () -> {});
        assertEquals("yes", settled(future), "and the answer is just the answer");
    }

    /**
     * An interrupt has to take the dialogs with it. A dialog is only ever opened by a tool, so the turn is busy for as
     * long as one waits. Killing the turn without this left the request behind: nobody could answer it, the caller
     * waited forever, and the caret turned into a question mark the instant the turn stopped being busy, pointing at a
     * question that was already gone.
     */
    @Test
    void interruptingCancelsWhicheverDialogWasWaiting() {
        for (var kind : List.<java.util.function.Function<DialogHandler, java.util.concurrent.Future<?>>>of(
                                        h -> h.confirm("sure?"), h -> h.prompt("name?", ""),
                                        h -> h.askQuestions(List.of(new SlashCommandTool.Question("pick", "Pick",
                                                                        List.of(new SlashCommandTool.Option("a", "")),
                                                                        false))))) {
            var handler = new DialogHandler(fakeHost());
            var pending = kind.apply(handler);

            assertTrue(handler.anyPending(), "opened");
            assertTrue(handler.cancelPending(), "an interrupt cancels it");

            // The caret is read straight off these three, so they must be false the moment the interrupt lands.
            assertFalse(handler.anyPending(), "nothing still pending, so the caret goes back to the plain one");
            var failure = assertThrows(Exception.class, () -> pending.get(2, java.util.concurrent.TimeUnit.SECONDS),
                                            "and the tool that asked stops waiting instead of hanging");
            assertTrue(failure instanceof java.util.concurrent.CancellationException
                                            || failure.getCause() instanceof java.util.concurrent.CancellationException,
                                            "cancelled, not merely timed out: " + failure);
        }
    }

    /** Nothing waiting, nothing to cancel: an interrupt with no dialog open must not claim it handled one. */
    @Test
    void cancellingWithNoDialogOpenReportsNothing() {
        assertFalse(new DialogHandler(fakeHost()).cancelPending());
    }

    /**
     * One door for a submitted line. The submit path used to try each text dialog in turn with the same arguments,
     * which put "which kinds can take a typed answer, and in what order" in the TUI rather than here.
     */
    @Test
    void aSubmittedLineGoesToWhicheverTextDialogIsWaiting() throws Exception {
        var idle = new DialogHandler(fakeHost());
        assertFalse(idle.submitAnswer("hi", () -> fail("nothing is waiting, so nothing may be echoed")));

        var toAQuestion = new DialogHandler(fakeHost());
        var question = toAQuestion.askQuestions(List.of(oneQuestion()));
        assertTrue(toAQuestion.submitAnswer("blue", () -> {}));
        assertEquals("blue", settled(question));

        var toAPrompt = new DialogHandler(fakeHost());
        var prompt = toAPrompt.prompt("name?", "fallback");
        assertTrue(toAPrompt.submitAnswer("  ", () -> {}), "blank still counts as answered; it takes the default");
        assertEquals("fallback", settled(prompt));

        // A confirm is answered by a key, never by a line, so it must not swallow one.
        var toAConfirm = new DialogHandler(fakeHost());
        var confirm = toAConfirm.confirm("sure?");
        assertFalse(toAConfirm.submitAnswer("y", () -> fail("a confirm does not take typed lines")));
        assertFalse(confirm.isDone());
    }
}
