package ai.pijota.telegram;

import ai.pijota.extensions.UiContext;
import ai.pijota.telegram.TelegramClient.InlineKeyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Implements the agent's interactive callbacks ({@link UiContext}) over a single Telegram chat. There is no status bar
 * or modal viewer in chat, so those degrade to no-ops or a plain message; choices become inline-keyboard buttons whose
 * taps complete the returned {@link CompletableFuture}, and a free-text {@code prompt} is answered by the chat's next
 * message. The owning {@link TelegramChat} feeds taps in via {@link #resolve} and messages via {@link #offerReply}.
 */
public final class TelegramUiContext implements UiContext {
    private final long chatId;
    private final TelegramApi tg;

    // Keyboard message id -> the open choice: how to apply a tapped value, plus how to render it back into the message.
    private final Map<Long, Choice> pendingChoices = new ConcurrentHashMap<>();
    // A free-text prompt awaiting the chat's next message (at most one outstanding).
    private final AtomicReference<CompletableFuture<String>> pendingPrompt = new AtomicReference<>();

    /**
     * A keyboard awaiting a tap. {@code prompt} is the original question; {@code apply} performs the side effect for
     * the tapped {@code callback_data}; {@code label} turns that data into the human choice shown back in the message
     * (or {@code null} for an ignored tap).
     */
    private record Choice(String prompt, Consumer<String> apply, Function<String, String> label) {
    }

    public TelegramUiContext(long chatId, TelegramApi tg) {
        this.chatId = chatId;
        this.tg = tg;
    }

    @Override
    public void setStatus(String message) {
        // A chat has no persistent status line; transient status is not shown.
    }

    @Override
    public void alert(String message) {
        tg.sendMessage(chatId, "ℹ️ " + message);
    }

    @Override
    public CompletableFuture<Boolean> confirm(String question) {
        var future = new CompletableFuture<Boolean>();
        long messageId = tg.sendMessage(chatId, question, InlineKeyboard.row(InlineKeyboard.button("Yes", "yes"),
                                        InlineKeyboard.button("No", "no")));
        pendingChoices.put(messageId, new Choice(question, data -> future.complete("yes".equals(data)),
                                        data -> "yes".equals(data) ? "Yes" : "No"));
        return future;
    }

    @Override
    public CompletableFuture<String> prompt(String question, String defaultValue) {
        var future = new CompletableFuture<String>();
        if (!pendingPrompt.compareAndSet(null, future)) {
            // Already waiting on an earlier prompt; don't lose it — fall back to the default rather than overwrite.
            return CompletableFuture.completedFuture(defaultValue);
        }
        tg.sendMessage(chatId, question);
        return future;
    }

    @Override
    public <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {
        var buttons = new ArrayList<InlineKeyboard.Button>(items.size());
        for (int i = 0; i < items.size(); i++)
            buttons.add(InlineKeyboard.button(render.apply(items.get(i)), Integer.toString(i)));
        long messageId = tg.sendMessage(chatId, title,
                                        InlineKeyboard.column(buttons.toArray(new InlineKeyboard.Button[0])));
        pendingChoices.put(messageId, new Choice(title, data -> {
            int idx = parseIndex(data);
            if (idx >= 0 && idx < items.size())
                onChoice.accept(items.get(idx));
        }, data -> {
            int idx = parseIndex(data);
            return idx >= 0 && idx < items.size() ? render.apply(items.get(idx)) : null;
        }));
    }

    @Override
    public void viewer(String title, List<String> lines) {
        // No scrollable viewer in chat, so deliver the content as a file attachment with the title as its caption.
        String body = String.join("\n", lines);
        if (body.isBlank())
            tg.sendMessage(chatId, title);
        else
            tg.sendDocument(chatId, "view.txt", body.getBytes(java.nio.charset.StandardCharsets.UTF_8), title);
    }

    /** Routes a button tap to whoever opened that keyboard. Returns true if it belonged to a pending choice. */
    boolean resolve(long messageId, String data) {
        var choice = pendingChoices.remove(messageId);
        if (choice == null)
            return false;
        choice.apply().accept(data);
        // Acknowledge the tap: rewrite the message to show the picked option and drop the now-stale buttons, so the
        // user sees their choice registered instead of a live keyboard that looks like nothing happened.
        String picked = choice.label().apply(data);
        if (picked != null) {
            try {
                tg.editMessageText(chatId, messageId, choice.prompt() + "\n\n✅ " + picked);
            } catch (RuntimeException ignored) {
                // best-effort feedback; the choice itself already took effect above
            }
        }
        return true;
    }

    /**
     * Offers the chat's next text message as the answer to a pending {@link #prompt}. Returns true if it consumed it.
     */
    boolean offerReply(String text) {
        var future = pendingPrompt.getAndSet(null);
        if (future == null)
            return false;
        future.complete(text);
        return true;
    }

    private static int parseIndex(String data) {
        try {
            return Integer.parseInt(data);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
