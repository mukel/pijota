package ai.pijota.telegram;

import ai.pijota.telegram.TelegramClient.CallbackQuery;
import ai.pijota.telegram.TelegramClient.InlineKeyboard;
import ai.pijota.telegram.TelegramClient.TgChat;
import ai.pijota.telegram.TelegramClient.TgMessage;
import ai.pijota.telegram.TelegramClient.Update;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TelegramFrontendTest {

    /** Records routed input so dispatch can be asserted without a real session. */
    private static final class RecordingChat implements ChatHandler {
        final List<String> texts = new ArrayList<>();
        final List<String> taps = new ArrayList<>();
        final List<String> photos = new ArrayList<>();
        final List<String> docs = new ArrayList<>();

        @Override
        public void onText(String text) {
            texts.add(text);
        }

        @Override
        public void onCallback(CallbackQuery callback) {
            taps.add(callback.data());
        }

        @Override
        public void onPhoto(List<TelegramClient.PhotoSize> photo, String caption) {
            photos.add(photo.get(photo.size() - 1).fileId() + ":" + caption);
        }

        @Override
        public void onDocument(TelegramClient.TgFile file, String caption) {
            docs.add(file.fileName() + ":" + file.mimeType() + ":" + caption);
        }
    }

    private static final class StubApi implements TelegramApi {
        @Override
        public List<Update> getUpdates(long offset, int timeoutSeconds) {
            return List.of();
        }

        @Override
        public long sendMessage(long chatId, String text) {
            return 0;
        }

        @Override
        public long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
            return 0;
        }

        @Override
        public void editMessageText(long chatId, long messageId, String text) {}

        @Override
        public void answerCallback(String callbackQueryId) {}

        @Override
        public void sendChatAction(long chatId, String action) {}
    }

    private static Update textUpdate(long updateId, long chatId, String text) {
        return new Update(updateId, new TgMessage(updateId * 10, new TgChat(chatId), text), null);
    }

    private static Update tapUpdate(long updateId, long chatId, String data) {
        return new Update(updateId, null, new CallbackQuery("cq", new TgMessage(5, new TgChat(chatId), null), data));
    }

    private TelegramFrontend frontend(ConcurrentHashMap<Long, RecordingChat> chats, AtomicInteger created,
                                    java.util.function.LongPredicate allow) {
        return new TelegramFrontend(new StubApi(), chatId -> {
            created.incrementAndGet();
            return chats.computeIfAbsent(chatId, id -> new RecordingChat());
        }, allow);
    }

    @Test
    void routesTextAndTapsToTheRightChat() {
        var chats = new ConcurrentHashMap<Long, RecordingChat>();
        var created = new AtomicInteger();
        var f = frontend(chats, created, id -> true);

        f.handle(textUpdate(1, 100, "hello"));
        f.handle(tapUpdate(2, 100, "yes"));
        f.handle(textUpdate(3, 200, "hi from another chat"));

        assertEquals(List.of("hello"), chats.get(100L).texts);
        assertEquals(List.of("yes"), chats.get(100L).taps);
        assertEquals(List.of("hi from another chat"), chats.get(200L).texts);
    }

    @Test
    void routesPhotoAndDocumentMessages() {
        var chats = new ConcurrentHashMap<Long, RecordingChat>();
        var f = frontend(chats, new AtomicInteger(), id -> true);

        var photo = new TgMessage(10, new TgChat(100), null, "look",
                                        List.of(new TelegramClient.PhotoSize("small", 1, 1, 9),
                                                                        new TelegramClient.PhotoSize("big", 9, 9, 99)),
                                        null, null, null, null);
        var doc = new TgMessage(11, new TgChat(100), null, "read", null,
                                        new TelegramClient.TgFile("fid", "spec.pdf", "application/pdf", 1234), null,
                                        null, null);
        f.handle(new Update(1, photo, null));
        f.handle(new Update(2, doc, null));

        assertEquals(List.of("big:look"), chats.get(100L).photos, "largest photo size routed with caption");
        assertEquals(List.of("spec.pdf:application/pdf:read"), chats.get(100L).docs);
    }

    @Test
    void reusesOneHandlerPerChat() {
        var chats = new ConcurrentHashMap<Long, RecordingChat>();
        var created = new AtomicInteger();
        var f = frontend(chats, created, id -> true);

        f.handle(textUpdate(1, 100, "a"));
        f.handle(textUpdate(2, 100, "b"));

        assertEquals(2, chats.get(100L).texts.size());
        assertEquals(1, created.get(), "the chat handler is created once and cached");
    }

    @Test
    void deniesChatsOutsideTheAllowList() {
        var chats = new ConcurrentHashMap<Long, RecordingChat>();
        var created = new AtomicInteger();
        var f = frontend(chats, created, id -> id == 100L); // only chat 100 is allowed

        f.handle(textUpdate(1, 999, "let me in"));

        assertTrue(chats.isEmpty(), "a denied chat is never even constructed");
        assertEquals(0, created.get());
    }

    @Test
    void ignoresUpdatesWithoutTextOrCallback() {
        var chats = new ConcurrentHashMap<Long, RecordingChat>();
        var f = frontend(chats, new AtomicInteger(), id -> true);

        f.handle(new Update(1, null, null)); // e.g. a join/leave/photo we don't handle
        assertTrue(chats.isEmpty());
    }

    @Test
    void aThrowingUpdateNeitherKillsTheLoopNorSkipsTheBatch() throws Exception {
        var recording = new RecordingChat();
        ChatHandler throwing = new ChatHandler() {
            @Override
            public void onText(String text) {
                throw new RuntimeException("boom");
            }

            @Override
            public void onCallback(CallbackQuery callback) {}
        };
        // one batch: a broken chat's update, then a good chat's update; the good one must still be delivered
        var batch = new java.util.concurrent.atomic.AtomicReference<List<Update>>(
                                        List.of(textUpdate(1, 1, "boom"), textUpdate(2, 2, "ok")));
        TelegramApi api = new TelegramApi() {
            @Override
            public List<Update> getUpdates(long offset, int timeout) {
                return batch.getAndSet(List.of());
            }

            @Override
            public long sendMessage(long chatId, String text) {
                return 0;
            }

            @Override
            public long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
                return 0;
            }

            @Override
            public void editMessageText(long chatId, long messageId, String text) {}

            @Override
            public void answerCallback(String callbackQueryId) {}

            @Override
            public void sendChatAction(long chatId, String action) {}
        };
        var f = new TelegramFrontend(api, id -> id == 1 ? throwing : recording, id -> true);

        var thread = new Thread(f::run, "test-telegram-poll");
        thread.setDaemon(true);
        thread.start();
        long deadline = System.currentTimeMillis() + 3000;
        while (recording.texts.isEmpty() && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        f.stop();
        thread.join(2000);

        assertEquals(List.of("ok"), recording.texts, "the good update is still delivered after a prior one threw");
        assertFalse(thread.isAlive(), "the poll loop survived and stopped cleanly");
    }
}
