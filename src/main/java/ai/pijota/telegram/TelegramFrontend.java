package ai.pijota.telegram;

import ai.pijota.telegram.TelegramClient.Update;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/**
 * Drives the bot: long-polls Telegram for updates and routes each to the right {@link ChatHandler} (one per chat id,
 * created lazily). Access is gated by an {@code allow} predicate so a personal agent bot only answers its owner. The
 * {@link ChatHandler} factory is injected, so this class never touches {@code PijotaSession} directly and its routing
 * is unit-testable with a fake {@link TelegramApi} and fake handlers.
 */
public final class TelegramFrontend {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TelegramFrontend.class);
    private static final int POLL_TIMEOUT_SECONDS = 30;
    private static final long ERROR_BACKOFF_MS = 2000;

    private final TelegramApi tg;
    private final LongFunction<ChatHandler> chatFactory;
    private final LongPredicate allow;
    private final Map<Long, ChatHandler> chats = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public TelegramFrontend(TelegramApi tg, LongFunction<ChatHandler> chatFactory, LongPredicate allow) {
        this.tg = tg;
        this.chatFactory = chatFactory;
        this.allow = allow;
    }

    /** Blocks, long-polling and dispatching until {@link #stop()}. */
    public void run() {
        running.set(true);
        long offset = 0;
        while (running.get()) {
            try {
                for (var update : tg.getUpdates(offset, POLL_TIMEOUT_SECONDS)) {
                    offset = Math.max(offset, update.updateId() + 1); // ack everything up to here on the next poll
                    try {
                        handle(update);
                    } catch (Throwable perUpdate) {
                        // one bad update must not skip the rest of the batch nor kill the poll loop
                        log.warn("telegram update handling failed: {}", perUpdate.toString());
                    }
                }
            } catch (Throwable e) {
                // the poll loop must never die — back off and keep going on ANY failure (network, API, etc.)
                log.warn("telegram poll failed; backing off: {}", e.toString());
                sleep(ERROR_BACKOFF_MS);
            }
        }
    }

    public void stop() {
        running.set(false);
    }

    /** Routes one update to its chat. Package-private so the poll loop and tests share the same dispatch. */
    void handle(Update update) {
        if (update.message() != null && update.message().text() != null && update.message().chat() != null) {
            long chatId = update.message().chat().id();
            if (allow.test(chatId))
                chatFor(chatId).onText(update.message().text());
        } else if (update.message() != null && update.message().photo() != null && !update.message().photo().isEmpty()
                                        && update.message().chat() != null) {
            long chatId = update.message().chat().id();
            if (allow.test(chatId))
                chatFor(chatId).onPhoto(update.message().photo(), update.message().caption());
        } else if (update.message() != null && update.message().file() != null && update.message().chat() != null) {
            long chatId = update.message().chat().id();
            if (allow.test(chatId))
                chatFor(chatId).onDocument(update.message().file(), update.message().caption());
        } else if (update.callbackQuery() != null && update.callbackQuery().message() != null
                                        && update.callbackQuery().message().chat() != null) {
            long chatId = update.callbackQuery().message().chat().id();
            if (allow.test(chatId))
                chatFor(chatId).onCallback(update.callbackQuery());
        }
    }

    private ChatHandler chatFor(long chatId) {
        return chats.computeIfAbsent(chatId, chatFactory::apply);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
