package org.pijota.tui;

import org.pijota.tools.SlashCommandTool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class DialogHandler {
    interface Host extends TuiHost {
        void clearStatus();
    }

    private final Host host;
    // Each dialog kind is an input request opened on the agent/tool thread and settled on the UI thread; PendingInput
    // owns that cross-thread lifecycle (see its docs) so all three stay correct uniformly.
    private final PendingInput<Boolean> confirmReq = new PendingInput<>();
    private final PendingInput<String> promptReq = new PendingInput<>();
    /**
     * One request for the whole set, not one per question: the set is walked synchronously, so its future is live for
     * exactly as long as a question is on screen, and that one fact answers "is a question waiting" for the caret, the
     * keys and the cancel path alike.
     */
    private final PendingInput<String> questionSet = new PendingInput<>();
    /** The set being walked one question at a time, and the answers so far; {@code answers.size()} is the cursor. */
    private List<SlashCommandTool.Question> asked = List.of();
    private List<String> answers = new ArrayList<>();
    private String pendingPromptDefault = "";

    DialogHandler(Host host) {
        this.host = host;
    }

    CompletableFuture<Boolean> confirm(String question) {
        var future = confirmReq.open();
        // No "[y/n]" on the question: the caret says it, one row below, and saying it twice reads as being asked
        // twice. They were a status line before that, which rendered in the warning slot beneath the stats row --
        // measured, eleven rows below the question they belonged to, dim yellow, reading as a stray warning.
        host.appendPlain(List.of(host.styled("toolLabel", "confirm"), question));
        host.redrawPrompt();
        return future;
    }

    CompletableFuture<String> prompt(String question, String defaultValue) {
        var future = promptReq.open();
        pendingPromptDefault = defaultValue != null ? defaultValue : "";
        host.appendPlain(List.of(host.styled("toolLabel", "prompt"), question));
        host.redrawPrompt();
        return future;
    }

    /**
     * Asks one question at a time, in order, and returns the answers together.
     *
     * <p>
     * They used to be printed all at once and answered with a single line. Every question restarted its options at
     * {@code 1)}, so "2" meant nothing on its own -- there was no way to say which question it answered, and nothing on
     * screen said which one was being asked. One at a time removes the ambiguity rather than explaining it: the
     * question on screen is the question you are answering.
     */
    CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        if (questions == null || questions.isEmpty())
            return CompletableFuture.completedFuture("");
        asked = questions;
        answers = new ArrayList<>();
        var future = questionSet.open();
        askOne(0);
        return future;
    }

    /**
     * Puts question {@code index} of the current set on screen.
     *
     * <p>
     * The set is walked from {@link #submitQuestionAnswer}, not by chaining a future per question: a chain hands the
     * next question to whichever pool thread happened to complete the previous one, so the terminal would be written
     * from off the UI thread, at a moment nothing else can order itself against.
     */
    private void askOne(int index) {
        var q = asked.get(index);
        int total = asked.size();
        var lines = new ArrayList<String>();
        lines.add(host.styled("toolLabel", "question"));
        // Where you are in the set, so a second question does not read as the first one repeating itself.
        if (total > 1) {
            String header = q.header() == null || q.header().isBlank() ? "" : "  " + q.header();
            lines.add(host.styled("statusText", (index + 1) + " of " + total + header));
        }
        lines.add(q.question());
        if (!q.options().isEmpty()) {
            for (int j = 0; j < q.options().size(); j++) {
                var opt = q.options().get(j);
                String desc = opt.description() == null || opt.description().isBlank() ? "" : " - " + opt.description();
                lines.add(String.format("    %d) %s%s", j + 1, opt.label(), desc));
            }
            // What kind of answer is accepted, kept in the transcript because that is the record; the live version of
            // it sits at the caret, where the eye is while typing.
            lines.add(host.styled("statusText", answerHint(q)));
        }
        host.appendPlain(lines);
        host.redrawPrompt();
    }

    /**
     * What kind of answer this question takes. One spelling, because the transcript prints it under the question and
     * the caret shows it live while you type: two copies drift, and the caret's copy used to offer numbers to a
     * question that had no options to number.
     */
    private static String answerHint(SlashCommandTool.Question q) {
        if (q.options().isEmpty())
            return "type your answer";
        return q.multiple() ? "type numbers (comma-separated), or your own text" : "type a number, or your own text";
    }

    /** One question answers as itself; several are labelled, so the model can tell which answer went with which. */
    private static String joinAnswers(List<SlashCommandTool.Question> questions, List<String> answers) {
        if (questions.size() == 1)
            return answers.isEmpty() ? "" : answers.get(0);
        var out = new StringBuilder();
        for (int i = 0; i < answers.size(); i++) {
            var q = questions.get(i);
            String label = q.header() == null || q.header().isBlank() ? "Q" + (i + 1) : q.header();
            out.append(i > 0 ? "\n" : "").append(label).append(": ").append(answers.get(i));
        }
        return out.toString();
    }

    boolean handleConfirm(Key key) {
        if (!confirmReq.active())
            return false;
        if (key instanceof Key.Char c) {
            if (c.value() == 'y' || c.value() == 'Y')
                confirmReq.fulfill(true);
            else if (c.value() == 'n' || c.value() == 'N')
                confirmReq.fulfill(false);
            else
                return true;
        } else if (key == Key.Special.ESC) {
            confirmReq.fulfill(false);
        } else {
            return true;
        }
        dialogClosed();
        return true;
    }

    /**
     * Only cancellation is intercepted; the text is the editor's.
     *
     * <p>
     * This used to be a second editor -- Char appended to a StringBuilder, Backspace trimmed it, and the result was
     * echoed into the status bar with a highlighted space for a cursor. So the interface had two things that looked
     * like input, and the live one was the wrong one: the real box sat empty directly above, which is where anyone
     * would type. One box, and everything the editor already knows (history, kill-word, undo, wide glyphs) comes with
     * it.
     */
    boolean handlePrompt(Key key) {
        if (!promptReq.active())
            return false;
        if (key == Key.Special.ESC || key == Key.Special.CTRL_C) {
            promptReq.fulfill(pendingPromptDefault);
            dialogClosed();
            return true;
        }
        return false; // everything else is text, and text belongs to the editor
    }

    /**
     * Hands the editor's line to whichever text dialog is waiting, and says whether one took it.
     *
     * <p>
     * A confirm is not here: it is answered by a single key, so it never reaches a submitted line. Which of the other
     * two is waiting is this class's business, not the submit path's -- only one can be open at a time anyway.
     */
    boolean submitAnswer(String answer, Runnable echo) {
        return submitQuestionAnswer(answer, echo) || submitPromptAnswer(answer, echo);
    }

    /** Fulfils a pending prompt with the editor's text; blank takes the default. Mirrors submitQuestionAnswer. */
    boolean submitPromptAnswer(String answer, Runnable echo) {
        if (!promptReq.active())
            return false;
        echo.run();
        promptReq.fulfill(answer == null || answer.isBlank() ? pendingPromptDefault : answer);
        dialogClosed();
        return true;
    }

    boolean submitQuestionAnswer(String answer, Runnable echo) {
        if (!questionSet.active())
            return false;
        echo.run();
        answers.add(answer);
        host.clearStatus();
        if (answers.size() < asked.size())
            askOne(answers.size()); // straight on to the next one, on this thread, before the caret is redrawn
        else
            questionSet.fulfill(joinAnswers(asked, answers));
        host.redrawPrompt();
        return true;
    }

    /**
     * What to show inside the empty prompt box while a dialog waits, or null when none does.
     *
     * <p>
     * Injected into the snapshot rather than into the editor, so it can never be accepted: the editor's own ghost is
     * insertable with →/End, and an instruction that types itself into the answer would be worse than no instruction.
     * At the caret because that is where the eye already is -- these used to be status lines, which render in the
     * warning slot and measured eleven rows below the question they belonged to.
     */
    String caretAffordance() {
        if (confirmReq.active())
            return "y / n";
        if (promptReq.active())
            return pendingPromptDefault == null || pendingPromptDefault.isBlank()
                                            ? "type a value · Enter to confirm · Esc cancel"
                                            : "Enter for " + pendingPromptDefault + " · Esc cancel";
        // No "Esc cancel" here, unlike the two above: a confirm and a prompt intercept the key themselves, but a
        // question does not, so Esc arms the interrupt instead. Promising a cancel the key does not perform is worse
        // than promising nothing.
        if (questionSet.active())
            return answerHint(asked.get(answers.size()));
        return null;
    }

    /**
     * Cancels whatever dialog is waiting, because the turn that asked has been interrupted.
     *
     * <p>
     * A dialog is opened by a tool, so the turn is always busy while one waits. Interrupting used to kill the asker and
     * leave the request behind: nothing could answer it, and the caret turned into a question mark the moment the turn
     * stopped being busy, pointing at a question that was already gone. Reached through
     * {@link org.pijota.agent.PijotaSession#abort()}, so every way of stopping a turn releases the dialog, not just the
     * one key that used to.
     */
    boolean cancelPending() {
        // Non-short-circuit: every kind is cancelled, not just the first one that happened to be active.
        boolean cancelled = confirmReq.cancel() | promptReq.cancel() | questionSet.cancel();
        if (cancelled)
            dialogClosed();
        return cancelled;
    }

    /** True while any dialog waits for an answer. The caret asks the same thing whichever kind it is. */
    boolean anyPending() {
        return confirmReq.active() || promptReq.active() || questionSet.active();
    }

    /**
     * The dialog is gone: clear the status line and redraw the caret without it.
     *
     * <p>
     * The handler writes no status of its own any more -- the keys live at the caret now. It still clears one, because
     * a dialog arrives on top of whatever hint was already there ("Tab to use", "no history to search yet"), and a hint
     * that outlives the thing it belonged to reads as describing whatever comes next.
     *
     * <p>
     * Not to be confused with {@link TuiHost#clearPrompt()}, which empties the editor and stashes the draft.
     */
    private void dialogClosed() {
        host.clearStatus();
        host.redrawPrompt();
    }
}
