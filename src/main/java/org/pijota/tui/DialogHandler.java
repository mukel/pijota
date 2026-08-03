package org.pijota.tui;

import org.pijota.tools.SlashCommandTool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class DialogHandler {
    interface Host extends TuiHost {
        void setStatus(String status);

        void clearStatus();
    }

    private final Host host;
    // Each dialog kind is an input request opened on the agent/tool thread and settled on the UI thread; PendingInput
    // owns that cross-thread lifecycle (see its docs) so all three stay correct uniformly.
    private final PendingInput<Boolean> confirmReq = new PendingInput<>();
    private final PendingInput<String> promptReq = new PendingInput<>();
    private final PendingInput<String> questionReq = new PendingInput<>();
    private StringBuilder pendingPromptText = new StringBuilder();
    private String pendingPromptDefault = "";

    DialogHandler(Host host) {
        this.host = host;
    }

    CompletableFuture<Boolean> confirm(String question) {
        var future = confirmReq.open();
        host.appendPlain(List.of(host.styled("toolLabel", "confirm"), question));
        // Not "confirm (y/n)": the transcript line one row up already says confirm, and a status line is for what to
        // press. Worded like the prompt dialog's, which never repeated its own name.
        host.setStatus("y to confirm, n to cancel");
        host.redrawPrompt();
        return future;
    }

    CompletableFuture<String> prompt(String question, String defaultValue) {
        var future = promptReq.open();
        pendingPromptText = new StringBuilder(defaultValue != null ? defaultValue : "");
        pendingPromptDefault = defaultValue != null ? defaultValue : "";
        host.appendPlain(List.of(host.styled("toolLabel", "prompt"), question));
        host.setStatus("type your response (Enter to confirm, Esc to cancel)");
        host.redrawPrompt();
        return future;
    }

    CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        var future = questionReq.open();
        var lines = new ArrayList<String>();
        lines.add(host.styled("toolLabel", "question"));
        for (int i = 0; i < questions.size(); i++) {
            var q = questions.get(i);
            if (questions.size() > 1)
                lines.add("");
            String prefix = questions.size() == 1 ? "" : "Q" + (i + 1) + "  ";
            lines.add(prefix + q.question());
            if (!q.options().isEmpty()) {
                for (int j = 0; j < q.options().size(); j++) {
                    var opt = q.options().get(j);
                    String desc = opt.description() == null || opt.description().isBlank()
                                                    ? ""
                                                    : " - " + opt.description();
                    lines.add(String.format("    %d) %s%s", j + 1, opt.label(), desc));
                }
                // No "(Esc cancel)": the footer says it live, in the interface's own chip grammar, so printing it
                // here too put the same instruction on screen twice in two different styles. This line says what kind
                // of answer is accepted, which the footer does not, and is what survives into the scrollback.
                lines.add(host.styled("statusText", q.multiple()
                                                ? "type numbers (comma-separated), or your own text"
                                                : "type a number, or your own text"));
            }
        }
        host.appendPlain(lines);
        host.setStatus("? type your answer · Esc cancel");
        host.redrawPrompt();
        return future;
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
        host.clearStatus();
        host.redrawPrompt();
        return true;
    }

    boolean handlePrompt(Key key) {
        if (!promptReq.active())
            return false;
        if (key instanceof Key.Char c) {
            pendingPromptText.append(c.value());
            host.redrawPrompt();
            return true;
        }
        return switch (key) {
            case Key.Special.ENTER -> {
                String result = pendingPromptText.isEmpty() ? pendingPromptDefault : pendingPromptText.toString();
                promptReq.fulfill(result);
                clearPrompt();
                yield true;
            }
            case Key.Special.ESC, Key.Special.CTRL_C -> {
                promptReq.fulfill(pendingPromptDefault);
                clearPrompt();
                yield true;
            }
            case Key.Special.BACKSPACE -> {
                if (!pendingPromptText.isEmpty()) {
                    pendingPromptText.setLength(pendingPromptText.length() - 1);
                    host.redrawPrompt();
                }
                yield true;
            }
            default -> true;
        };
    }

    boolean cancelQuestionIfIdle(boolean busy) {
        if (busy || !questionReq.cancel())
            return false;
        host.clearStatus();
        host.redrawPrompt();
        return true;
    }

    boolean submitQuestionAnswer(String answer, Runnable echo) {
        if (!questionReq.active())
            return false;
        echo.run();
        questionReq.fulfill(answer);
        host.clearStatus();
        host.redrawPrompt();
        return true;
    }

    String promptStatusLine() {
        if (!promptReq.active())
            return null;
        return host.styled("statusText", "▶ " + pendingPromptText) + host.styled("highlight", " ");
    }

    boolean questionPending() {
        return questionReq.active();
    }

    private void clearPrompt() {
        pendingPromptText = new StringBuilder();
        host.clearStatus();
        host.redrawPrompt();
    }
}
