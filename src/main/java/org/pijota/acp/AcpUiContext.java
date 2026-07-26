package org.pijota.acp;

import org.pijota.extensions.UiContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link UiContext} for the ACP frontend. ACP has no native picker/dialog, so interactive requests are posed to the
 * client as ordinary assistant text and answered by the user's next message: {@code confirm}/{@code select} emit a
 * prompt and arm a one-shot {@link #pendingReply}, and the frontend routes the next prompt through {@link #tryAnswer}
 * before handling it normally. Output-only callbacks (status/alert/output/viewer) are relayed verbatim.
 *
 * <p>
 * All client I/O goes through the {@code emit} sink the frontend supplies, keeping this class free of transport
 * concerns — the same split as {@code WebUiContext}/{@code TelegramUiContext}.
 */
final class AcpUiContext implements UiContext {

    private final Consumer<String> emit;

    /** A pending confirm/select awaiting the next message; returns true when it consumes the reply. */
    private volatile Predicate<String> pendingReply;

    AcpUiContext(Consumer<String> emit) {
        this.emit = emit;
    }

    /** Routes the user's next message to a pending confirm/select. Returns true if it was consumed as the answer. */
    boolean tryAnswer(String text) {
        Predicate<String> reply = pendingReply;
        if (reply == null)
            return false;
        pendingReply = null;
        return reply.test(text);
    }

    @Override
    public void cancelPending() {
        Predicate<String> reply = pendingReply;
        pendingReply = null;
        if (reply != null)
            reply.test("cancel"); // resolve an outstanding confirm/select as cancelled so the turn can't hang
    }

    @Override
    public void setStatus(String message) {
        emit.accept(message);
    }

    @Override
    public void alert(String message) {
        emit.accept(message);
    }

    @Override
    public void appendOutput(List<String> lines) {
        if (lines != null)
            lines.forEach(emit);
    }

    @Override
    public void viewer(String title, List<String> lines) {
        emit.accept(title);
        if (lines != null)
            lines.forEach(emit);
    }

    @Override
    public CompletableFuture<Boolean> confirm(String question) {
        var future = new CompletableFuture<Boolean>();
        emit.accept(question + "\n\n(reply \"yes\" or \"no\")");
        pendingReply = text -> {
            String t = text.strip().toLowerCase();
            if (t.equals("yes") || t.equals("y")) {
                future.complete(true);
                return true;
            }
            if (t.equals("no") || t.equals("n") || t.equals("cancel")) {
                future.complete(false);
                return true;
            }
            return false; // unrecognized — let the message be handled normally
        };
        return future;
    }

    @Override
    public <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {
        if (items == null || items.isEmpty())
            return;
        List<T> choices = List.copyOf(items);
        StringBuilder sb = new StringBuilder(title == null ? "Select one:" : title);
        for (int i = 0; i < choices.size(); i++)
            sb.append("\n  ").append(i + 1).append(". ").append(render.apply(choices.get(i)));
        sb.append("\n\n(reply with a number, or \"cancel\")");
        emit.accept(sb.toString());
        pendingReply = text -> {
            String t = text.strip();
            if (t.equalsIgnoreCase("cancel")) {
                emit.accept("Cancelled.");
                return true;
            }
            try {
                int idx = Integer.parseInt(t) - 1;
                if (idx >= 0 && idx < choices.size()) {
                    onChoice.accept(choices.get(idx));
                    return true;
                }
                emit.accept("Please pick a number between 1 and " + choices.size() + ".");
                return true;
            } catch (NumberFormatException e) {
                return false; // not a number — treat as a normal message
            }
        };
    }
}
