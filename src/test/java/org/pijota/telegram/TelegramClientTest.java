package org.pijota.telegram;

import org.pijota.telegram.TelegramClient.InlineKeyboard;
import org.pijota.telegram.TelegramClient.Update;
import org.pijota.testkit.FakeHttpClient;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the snake_case wire mapping (the only fiddly part) with canned payloads — no network needed. */
class TelegramClientTest {

    @Test
    void sendMessageReturnsTheNewMessageId() {
        var tg = new TelegramClient("tok", FakeHttpClient.ok("{\"ok\":true,\"result\":{\"message_id\":42}}"));
        assertEquals(42, tg.sendMessage(1, "hi"));
    }

    @Test
    void getUpdatesParsesTheUpdateList() {
        var tg = new TelegramClient("tok", FakeHttpClient.ok("{\"ok\":true,\"result\":[{\"update_id\":7,"
                                        + "\"message\":{\"message_id\":1,\"chat\":{\"id\":5},\"text\":\"hi\"}}]}"));
        var updates = tg.getUpdates(0, 0);
        assertEquals(1, updates.size());
        assertEquals(7, updates.getFirst().updateId());
        assertEquals("hi", updates.getFirst().message().text());
    }

    @Test
    void aRateLimitedCallIsRetried() {
        var tg = new TelegramClient("tok", FakeHttpClient.sequence(
                                        "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":1}}",
                                        "{\"ok\":true,\"result\":{\"message_id\":9}}"));
        assertEquals(9, tg.sendMessage(1, "hi"), "the 429 is retried, then the second response succeeds");
    }

    @Test
    void anApiErrorThrows() {
        var tg = new TelegramClient("tok", FakeHttpClient.ok("{\"ok\":false,\"description\":\"Bad Request\"}"));
        assertThrows(TelegramException.class, () -> tg.sendMessage(1, "hi"));
    }

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

    @Test
    void multipartBodyCarriesFieldsFilenameAndContent() {
        byte[] body = TelegramClient.multipartBody("BOUND", 1234, "answer.md", "# Title\nbody".getBytes(UTF_8), "cap");
        String s = new String(body, UTF_8);

        assertTrue(s.contains("--BOUND\r\n"), "uses the boundary");
        assertTrue(s.contains("name=\"chat_id\"\r\n\r\n1234"), "carries chat_id");
        assertTrue(s.contains("name=\"caption\"\r\n\r\ncap"), "carries caption");
        assertTrue(s.contains("name=\"document\"; filename=\"answer.md\""), "names the file");
        assertTrue(s.contains("# Title\nbody"), "includes the content");
        assertTrue(s.endsWith("--BOUND--\r\n"), "closes the multipart envelope");
    }

    @Test
    void multipartFilenameIsSanitized() {
        byte[] body = TelegramClient.multipartBody("BOUND", 1234, "bad\"\r\nname/path.md", "x".getBytes(UTF_8), null);
        String s = new String(body, UTF_8);

        assertTrue(s.contains("filename=\"bad___name_path.md\""), s);
        assertFalse(s.contains("filename=\"bad\""), s);
    }

    @Test
    void safeFilenameFallsBackForBlankNames() {
        assertEquals("file", TelegramClient.safeFilename(null));
        assertEquals("file", TelegramClient.safeFilename(""));
    }

    @Test
    void multipartBodyOmitsBlankCaption() {
        byte[] body = TelegramClient.multipartBody("B", 1, "f.txt", "x".getBytes(UTF_8), null);
        assertFalse(new String(body, UTF_8).contains("name=\"caption\""), "no caption part when none given");
    }

    @Test
    void retryAfterIsReadFromA429() throws Exception {
        var node = TelegramClient.TG.readTree("{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":7}}");
        assertEquals(7, TelegramClient.retryAfterSeconds(node));
    }

    @Test
    void retryAfterIsZeroForNon429Errors() throws Exception {
        var badRequest = TelegramClient.TG.readTree("{\"ok\":false,\"error_code\":400,\"description\":\"nope\"}");
        assertEquals(0, TelegramClient.retryAfterSeconds(badRequest));
        var ok = TelegramClient.TG.readTree("{\"ok\":true,\"result\":{}}");
        assertEquals(0, TelegramClient.retryAfterSeconds(ok));
        var noParams = TelegramClient.TG.readTree("{\"ok\":false,\"error_code\":429}");
        assertEquals(0, TelegramClient.retryAfterSeconds(noParams), "429 without parameters → 0");
    }
}
