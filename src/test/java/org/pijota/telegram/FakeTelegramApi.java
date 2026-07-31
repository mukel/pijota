package org.pijota.telegram;

import org.pijota.telegram.TelegramClient.InlineKeyboard;
import org.pijota.telegram.TelegramClient.Update;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recording {@link TelegramApi} fake: captures every message sent or edited (the HTML variants default to
 * {@link #sendMessage}/{@link #editMessageText}, so their content is captured too) plus chat actions and answered
 * callbacks, so a test can assert what the bot would have shown without any HTTP. Thread-safe because the agent streams
 * replies from a background thread.
 */
final class FakeTelegramApi implements TelegramApi {
    private final List<String> sent = new ArrayList<>();
    private final List<String> edits = new ArrayList<>();
    private final List<String> actions = new ArrayList<>();
    private final List<String> answeredCallbacks = new ArrayList<>();
    private final List<InlineKeyboard> keyboards = new ArrayList<>(); // keyboard on each keyboard-bearing send/edit, in
                                                                      // order
    private final List<Doc> documents = new ArrayList<>();
    private final AtomicLong nextMessageId = new AtomicLong(100);

    /** An uploaded document, content decoded as UTF-8 for easy assertion. */
    record Doc(String filename, String content, String caption) {
    }

    /**
     * Every keyboard passed to a streamed send/edit, in order, e.g. assert a Stop button appeared then was dropped.
     */
    synchronized List<InlineKeyboard> keyboards() {
        return new ArrayList<>(keyboards); // ArrayList (not List.copyOf): the list intentionally holds nulls (no
                                           // keyboard)
    }

    synchronized List<Doc> documents() {
        return List.copyOf(documents);
    }

    synchronized List<String> sent() {
        return List.copyOf(sent);
    }

    /** All message text the bot would display this turn, both freshly sent and edited-in-place. */
    synchronized List<String> allText() {
        var all = new ArrayList<>(sent);
        all.addAll(edits);
        return all;
    }

    synchronized List<String> actions() {
        return List.copyOf(actions);
    }

    synchronized List<String> answeredCallbacks() {
        return List.copyOf(answeredCallbacks);
    }

    @Override
    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        return List.of();
    }

    @Override
    public synchronized long sendMessage(long chatId, String text) {
        sent.add(text);
        return nextMessageId.getAndIncrement();
    }

    @Override
    public synchronized long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
        sent.add(text);
        keyboards.add(keyboard);
        return nextMessageId.getAndIncrement();
    }

    // The HTML variants carry the Stop-button keyboard on the streamed message; capture text AND keyboard (the default
    // impls would route text through sendMessage/editMessageText but drop editHtml's keyboard).
    @Override
    public synchronized long sendHtml(long chatId, String html, InlineKeyboard keyboard) {
        sent.add(html);
        keyboards.add(keyboard);
        return nextMessageId.getAndIncrement();
    }

    @Override
    public synchronized void editHtml(long chatId, long messageId, String html, InlineKeyboard keyboard) {
        edits.add(html);
        keyboards.add(keyboard);
    }

    @Override
    public synchronized long sendDocument(long chatId, String filename, byte[] content, String caption) {
        documents.add(new Doc(filename, new String(content, StandardCharsets.UTF_8), caption));
        return nextMessageId.getAndIncrement();
    }

    @Override
    public synchronized void editMessageText(long chatId, long messageId, String text) {
        edits.add(text);
    }

    @Override
    public synchronized void answerCallback(String callbackQueryId) {
        answeredCallbacks.add(callbackQueryId);
    }

    @Override
    public synchronized void sendChatAction(long chatId, String action) {
        actions.add(action);
    }

    private volatile byte[] downloadPayload;

    /** Stubs the bytes {@link #downloadFile} returns (null by default → simulates a failed download). */
    FakeTelegramApi onDownload(byte[] bytes) {
        this.downloadPayload = bytes;
        return this;
    }

    @Override
    public byte[] downloadFile(String fileId) {
        return downloadPayload;
    }
}
