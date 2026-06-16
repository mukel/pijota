package ai.pijota.telegram;

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
}
