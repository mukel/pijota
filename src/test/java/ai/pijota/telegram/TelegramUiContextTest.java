package ai.pijota.telegram;

import ai.pijota.telegram.TelegramClient.InlineKeyboard;
import ai.pijota.telegram.TelegramClient.Update;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TelegramUiContextTest {

    /** Records what was sent and hands out message ids, so the callback routing can be tested without HTTP. */
    private static final class FakeApi implements TelegramApi {
        record Sent(long chatId, String text, InlineKeyboard keyboard) {
        }

        record Edit(long messageId, String text) {
        }

        final List<Sent> sent = new ArrayList<>();
        final List<Edit> edits = new ArrayList<>();
        private final AtomicLong nextId = new AtomicLong(100);

        @Override
        public List<Update> getUpdates(long offset, int timeoutSeconds) {
            return List.of();
        }

        @Override
        public long sendMessage(long chatId, String text) {
            return sendMessage(chatId, text, null);
        }

        @Override
        public long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
            sent.add(new Sent(chatId, text, keyboard));
            return nextId.getAndIncrement();
        }

        @Override
        public void editMessageText(long chatId, long messageId, String text) {
            edits.add(new Edit(messageId, text));
        }

        @Override
        public void answerCallback(String callbackQueryId) {}

        @Override
        public void sendChatAction(long chatId, String action) {}
    }

    @Test
    void confirmSendsButtonsAndResolvesOnTap() {
        var api = new FakeApi();
        var ui = new TelegramUiContext(7, api);

        var answer = ui.confirm("Delete it?");
        assertFalse(answer.isDone(), "stays pending until the user taps");
        assertEquals(1, api.sent.size());
        assertNotNull(api.sent.get(0).keyboard(), "confirm shows an inline keyboard");
        long keyboardMsgId = 100; // first id handed out by the fake

        assertTrue(ui.resolve(keyboardMsgId, "yes"));
        assertTrue(answer.isDone());
        assertTrue(answer.join());

        // The tap is acknowledged: the keyboard message is rewritten to show the choice (and, with no reply_markup on
        // the edit, the buttons are dropped) — so the user doesn't see a dead keyboard that looks like nothing
        // happened.
        assertEquals(1, api.edits.size());
        assertEquals(keyboardMsgId, api.edits.get(0).messageId());
        assertTrue(api.edits.get(0).text().contains("Delete it?"), api.edits.get(0).text());
        assertTrue(api.edits.get(0).text().contains("✅ Yes"), api.edits.get(0).text());
    }

    @Test
    void selectTapEchoesTheChosenLabel() {
        var api = new FakeApi();
        var ui = new TelegramUiContext(7, api);
        ui.select("Pick a model", List.of("gpt", "claude"), s -> s.toUpperCase(), s -> {});

        ui.resolve(100, "1"); // index 1 = "claude" → label "CLAUDE"
        assertEquals(1, api.edits.size());
        assertTrue(api.edits.get(0).text().contains("✅ CLAUDE"), api.edits.get(0).text());
    }

    @Test
    void confirmNoResolvesFalse() {
        var api = new FakeApi();
        var ui = new TelegramUiContext(7, api);
        var answer = ui.confirm("Proceed?");
        ui.resolve(100, "no");
        assertFalse(answer.join());
    }

    @Test
    void promptIsAnsweredByNextMessage() {
        var api = new FakeApi();
        var ui = new TelegramUiContext(7, api);

        var answer = ui.prompt("What's the branch name?", "main");
        assertFalse(answer.isDone());
        assertTrue(ui.offerReply("feature/x"));
        assertEquals("feature/x", answer.join());
    }

    @Test
    void secondConcurrentPromptFallsBackToDefaultRatherThanLosingTheFirst() {
        var api = new FakeApi();
        var ui = new TelegramUiContext(7, api);

        var first = ui.prompt("Q1", "d1");
        var second = ui.prompt("Q2", "d2");
        assertEquals("d2", second.join(), "second prompt returns its default immediately");
        assertFalse(first.isDone(), "first prompt is still pending");
        assertTrue(ui.offerReply("answer-to-first"));
        assertEquals("answer-to-first", first.join());
    }

    @Test
    void selectMapsTheTappedIndexBackToTheDomainObject() {
        var api = new FakeApi();
        var ui = new TelegramUiContext(7, api);
        var chosen = new ArrayList<String>();

        ui.select("Pick a model", List.of("gpt", "claude", "gemini"), s -> s.toUpperCase(), chosen::add);
        assertEquals(1, api.sent.size());
        assertEquals(3, api.sent.get(0).keyboard().inlineKeyboard().size(), "one button per choice (column)");

        assertTrue(ui.resolve(100, "1")); // index 1 = "claude"
        assertEquals(List.of("claude"), chosen);
    }

    @Test
    void unknownCallbackIsIgnored() {
        var api = new FakeApi();
        var ui = new TelegramUiContext(7, api);
        assertFalse(ui.resolve(999, "whatever"), "a tap with no pending choice is a no-op");
    }
}
