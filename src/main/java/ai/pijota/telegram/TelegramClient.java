package ai.pijota.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * A minimal hand-rolled client over the Telegram Bot HTTP API — just the handful of methods a chat frontend needs
 * (long-poll {@code getUpdates}, send/edit messages, answer button taps, show a typing indicator). Every call is an
 * HTTPS POST of a JSON body; the response is {@code {"ok":bool,"result":...}}. Self-contained: one {@link HttpClient}
 * and one dedicated {@link ObjectMapper} configured for Telegram's snake_case wire format, so the DTOs below stay plain
 * camelCase records with no annotations. Blocking and thread-confined to the caller's poll loop.
 */
public final class TelegramClient implements TelegramApi {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60); // must exceed the long-poll timeout

    // Telegram speaks snake_case and sends many fields we ignore; this mapper translates both ways and never throws on
    // unknown properties, so the records need no @JsonProperty plumbing.
    static final ObjectMapper TG = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;

    public TelegramClient(String token) {
        this.base = "https://api.telegram.org/bot" + token;
    }

    /** Long-polls for new updates; blocks up to {@code timeoutSeconds} server-side. Pass {@code lastUpdateId + 1}. */
    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        return updatesFrom(call("getUpdates", new GetUpdatesReq(offset, timeoutSeconds)));
    }

    /** Sends a message (plain text — markdown conversion is the frontend's job); returns the new message id. */
    public long sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    public long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
        return call("sendMessage", new SendMessageReq(chatId, text, keyboard)).path("message_id").asLong();
    }

    /** Rewrites an already-sent message — used to "stream" by coalescing partial answers into one edited message. */
    public void editMessageText(long chatId, long messageId, String text) {
        call("editMessageText", new EditMessageReq(chatId, messageId, text));
    }

    /** Acknowledges a button tap so Telegram stops showing the loading spinner on the button. */
    public void answerCallback(String callbackQueryId) {
        call("answerCallbackQuery", new CallbackAnswerReq(callbackQueryId));
    }

    /** Shows a transient "typing…" indicator in the chat (clears itself after ~5s or on the next message). */
    public void sendChatAction(long chatId, String action) {
        call("sendChatAction", new ChatActionReq(chatId, action));
    }

    // ---- transport ----

    private JsonNode call(String method, Object payload) {
        try {
            var request = HttpRequest.newBuilder(URI.create(base + "/" + method)).timeout(REQUEST_TIMEOUT)
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(TG.writeValueAsString(payload)))
                                            .build();
            var response = http.send(request, BodyHandlers.ofString());
            JsonNode root = TG.readTree(response.body());
            if (!root.path("ok").asBoolean()) {
                throw new TelegramException(root.path("description").asText("telegram error " + response.statusCode()));
            }
            return root.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("telegram call interrupted: " + method, e);
        } catch (java.io.IOException e) {
            throw new TelegramException("telegram call failed: " + method + ": " + e.getMessage(), e);
        }
    }

    static List<Update> updatesFrom(JsonNode resultArray) {
        return List.of(TG.convertValue(resultArray, Update[].class));
    }

    // ---- incoming DTOs (only the fields a frontend needs; the lenient mapper drops the rest) ----

    public record Update(long updateId, TgMessage message, CallbackQuery callbackQuery) {
    }

    public record TgMessage(long messageId, TgChat chat, String text) {
    }

    public record TgChat(long id) {
    }

    public record CallbackQuery(String id, TgMessage message, String data) {
    }

    // ---- outgoing DTOs ----

    /** An inline button grid. {@code callback_data} comes back verbatim in a {@link CallbackQuery} when tapped. */
    public record InlineKeyboard(List<List<Button>> inlineKeyboard) {
        public record Button(String text, String callbackData) {
        }

        public static Button button(String text, String callbackData) {
            return new Button(text, callbackData);
        }

        /** One horizontal row — e.g. Yes / No. */
        public static InlineKeyboard row(Button... buttons) {
            return new InlineKeyboard(List.of(List.of(buttons)));
        }

        /** One button per row (vertical) — better for a long list of choices. */
        public static InlineKeyboard column(Button... buttons) {
            return new InlineKeyboard(Stream.of(buttons).map(List::of).toList());
        }
    }

    private record GetUpdatesReq(long offset, int timeout) {
    }

    private record SendMessageReq(long chatId, String text, InlineKeyboard replyMarkup) {
    }

    private record EditMessageReq(long chatId, long messageId, String text) {
    }

    private record CallbackAnswerReq(String callbackQueryId) {
    }

    private record ChatActionReq(long chatId, String action) {
    }
}
