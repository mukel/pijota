package ai.pijota.telegram;

import ai.pijota.telegram.TelegramClient.InlineKeyboard;
import ai.pijota.telegram.TelegramClient.Update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the snake_case wire mapping (the only fiddly part) with canned payloads — no network needed. */
class TelegramClientTest {

    @Test
    void parsesATextMessageUpdate() throws Exception {
        String json = """
                                        [{"update_id": 42,
                                          "message": {"message_id": 7, "text": "hello bot",
                                                      "chat": {"id": 1234, "type": "private"},
                                                      "from": {"id": 9, "is_bot": false}}}]
                                        """;
        var node = TelegramClient.TG.readTree(json);
        var updates = TelegramClient.updatesFrom(node);

        assertEquals(1, updates.size());
        Update u = updates.get(0);
        assertEquals(42, u.updateId());
        assertNull(u.callbackQuery());
        assertEquals(7, u.message().messageId());
        assertEquals("hello bot", u.message().text());
        assertEquals(1234, u.message().chat().id());
    }

    @Test
    void parsesACallbackQueryUpdate() throws Exception {
        String json = """
                                        [{"update_id": 43,
                                          "callback_query": {"id": "cq1", "data": "yes",
                                                             "message": {"message_id": 8, "chat": {"id": 1234}}}}]
                                        """;
        var updates = TelegramClient.updatesFrom(TelegramClient.TG.readTree(json));

        var cq = updates.get(0).callbackQuery();
        assertNotNull(cq);
        assertEquals("cq1", cq.id());
        assertEquals("yes", cq.data());
        assertEquals(1234, cq.message().chat().id());
    }

    @Test
    void serializesInlineKeyboardToTelegramShape() throws Exception {
        var kb = InlineKeyboard.row(InlineKeyboard.button("Yes", "yes"), InlineKeyboard.button("No", "no"));
        String json = TelegramClient.TG.writeValueAsString(kb);

        // Telegram expects {"inline_keyboard":[[{"text":..,"callback_data":..}, ...]]}
        assertTrue(json.contains("\"inline_keyboard\""), json);
        assertTrue(json.contains("\"callback_data\":\"yes\""), json);
        assertFalse(json.contains("callbackData"), "must be snake_case on the wire");
    }

    @Test
    void columnKeyboardIsOneButtonPerRow() throws Exception {
        var kb = InlineKeyboard.column(InlineKeyboard.button("A", "a"), InlineKeyboard.button("B", "b"));
        assertEquals(2, kb.inlineKeyboard().size(), "each choice on its own row");
        assertEquals(1, kb.inlineKeyboard().get(0).size());
    }
}
