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

    // Keyboard message id -> what to do with the tapped button's callback_data.
    private final Map<Long, Consumer<String>> pendingChoices = new ConcurrentHashMap<>();
    // A free-text prompt awaiting the chat's next message (at most one outstanding).
    private final AtomicReference<CompletableFuture<String>> pendingPrompt = new AtomicReference<>();

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
        pendingChoices.put(messageId, data -> future.complete("yes".equals(data)));
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
        pendingChoices.put(messageId, data -> {
            int idx = parseIndex(data);
            if (idx >= 0 && idx < items.size())
                onChoice.accept(items.get(idx));
        });
    }

    @Override
    public void viewer(String title, List<String> lines) {
        // No scrollable viewer in chat; the frontend may split/attach long content. Keep it minimal here.
        tg.sendMessage(chatId, title);
    }

    /** Routes a button tap to whoever opened that keyboard. Returns true if it belonged to a pending choice. */
    boolean resolve(long messageId, String data) {
        var handler = pendingChoices.remove(messageId);
        if (handler == null)
            return false;
        handler.accept(data);
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
