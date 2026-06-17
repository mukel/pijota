package ai.pijota.telegram;

import ai.pijota.telegram.TelegramClient.BotCommand;
import ai.pijota.telegram.TelegramClient.InlineKeyboard;
import ai.pijota.telegram.TelegramClient.Update;

import java.util.List;

/**
 * The Telegram Bot operations a frontend needs, as a seam so {@link TelegramChat} / {@link TelegramUiContext} can be
 * driven by a fake in tests without real HTTP. {@link TelegramClient} is the production implementation.
 */
public interface TelegramApi {
    List<Update> getUpdates(long offset, int timeoutSeconds);

    long sendMessage(long chatId, String text);

    long sendMessage(long chatId, String text, InlineKeyboard keyboard);

    void editMessageText(long chatId, long messageId, String text);

    void answerCallback(String callbackQueryId);

    void sendChatAction(long chatId, String action);

    // HTML-rendered variants and the command menu. Defaulted so test fakes that only care about plain text keep
    // compiling; the production TelegramClient overrides them to set parse_mode / register commands.

    default long sendHtml(long chatId, String html, InlineKeyboard keyboard) {
        return sendMessage(chatId, html, keyboard);
    }

    default void editHtml(long chatId, long messageId, String html, InlineKeyboard keyboard) {
        editMessageText(chatId, messageId, html);
    }

    default void setMyCommands(List<BotCommand> commands) {
        // no-op for fakes
    }

    default long sendDocument(long chatId, String filename, byte[] content, String caption) {
        return 0; // fakes that don't exercise uploads ignore them
    }

    default void setReaction(long chatId, long messageId, String emoji) {
        // no-op for fakes
    }
}
