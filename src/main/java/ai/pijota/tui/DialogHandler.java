package ai.pijota.tui;

import ai.pijota.tools.SlashCommandTool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class DialogHandler {
    interface Host {
        void appendPlain(List<String> lines);

        String styled(String slot, String text);

        void setStatus(String status);

        void clearStatus();

        void redrawPrompt();
    }

    private final Host host;
    private CompletableFuture<String> pendingQuestionAnswer;
    private volatile CompletableFuture<Boolean> pendingConfirm;
    private volatile CompletableFuture<String> pendingPrompt;
    private StringBuilder pendingPromptText = new StringBuilder();
    private String pendingPromptDefault = "";

    DialogHandler(Host host) {
        this.host = host;
    }

    CompletableFuture<Boolean> confirm(String question) {
        var future = new CompletableFuture<Boolean>();
        pendingConfirm = future;
        host.appendPlain(List.of(host.styled("toolLabel", "confirm"), question));
        host.setStatus("confirm (y/n)");
        host.redrawPrompt();
        return future;
    }

    CompletableFuture<String> prompt(String question, String defaultValue) {
        var future = new CompletableFuture<String>();
        pendingPrompt = future;
        pendingPromptText = new StringBuilder(defaultValue != null ? defaultValue : "");
        pendingPromptDefault = defaultValue != null ? defaultValue : "";
        host.appendPlain(List.of(host.styled("toolLabel", "prompt"), question));
        host.setStatus("type your response (Enter to confirm, Esc to cancel)");
        host.redrawPrompt();
        return future;
    }

    CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        var future = new CompletableFuture<String>();
        pendingQuestionAnswer = future;
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
                lines.add(host.styled("statusText", q.multiple()
                                                ? "type numbers (comma-separated) or custom text (Esc cancel)"
                                                : "type a number or custom text (Esc cancel)"));
            } else {
                lines.add(host.styled("statusText", "type your answer (Esc cancel)"));
            }
        }
        host.appendPlain(lines);
        host.setStatus("answer (" + questions.size() + ")");
        host.redrawPrompt();
        return future;
    }

    boolean handleConfirm(Key key) {
        var pc = pendingConfirm;
        if (pc == null || pc.isDone())
            return false;
        if (key instanceof Key.Char c) {
            if (c.value() == 'y' || c.value() == 'Y')
                pc.complete(true);
            else if (c.value() == 'n' || c.value() == 'N')
                pc.complete(false);
            else
                return true;
        } else if (key == Key.Special.ESC) {
            pc.complete(false);
        } else {
            return true;
        }
        pendingConfirm = null;
        host.clearStatus();
        host.redrawPrompt();
        return true;
    }

    boolean handlePrompt(Key key) {
        var pp = pendingPrompt;
        if (pp == null || pp.isDone())
            return false;
        if (key instanceof Key.Char c) {
            pendingPromptText.append(c.value());
            host.redrawPrompt();
            return true;
        }
        return switch (key) {
            case Key.Special.ENTER -> {
                String result = pendingPromptText.isEmpty() ? pendingPromptDefault : pendingPromptText.toString();
                pp.complete(result);
                clearPrompt();
                yield true;
            }
            case Key.Special.ESC, Key.Special.CTRL_C -> {
                pp.complete(pendingPromptDefault);
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
        var pending = pendingQuestionAnswer;
        if (busy || pending == null || pending.isDone())
            return false;
        pending.cancel(true);
        pendingQuestionAnswer = null;
        host.clearStatus();
        host.redrawPrompt();
        return true;
    }

    boolean submitQuestionAnswer(String answer, Runnable echo) {
        var pending = pendingQuestionAnswer;
        if (pending == null || pending.isDone())
            return false;
        echo.run();
        pending.complete(answer);
        pendingQuestionAnswer = null;
        host.clearStatus();
        host.redrawPrompt();
        return true;
    }

    String promptStatusLine() {
        if (pendingPrompt == null || pendingPrompt.isDone())
            return null;
        return host.styled("statusText", "▶ " + pendingPromptText) + host.styled("highlight", " ");
    }

    boolean questionPending() {
        return pendingQuestionAnswer != null;
    }

    private void clearPrompt() {
        pendingPrompt = null;
        pendingPromptText = new StringBuilder();
        host.clearStatus();
        host.redrawPrompt();
    }
}
