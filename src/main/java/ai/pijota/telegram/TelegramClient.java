package ai.pijota.telegram;

import ai.pijota.util.Env;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Proxies;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A minimal hand-rolled client over the Telegram Bot HTTP API — just the handful of methods a chat frontend needs
 * (long-poll {@code getUpdates}, send/edit messages, answer button taps, show a typing indicator). Every call is an
 * HTTPS POST of a JSON body; the response is {@code {"ok":bool,"result":...}}. Self-contained: one {@link HttpClient}
 * and one dedicated {@link ObjectMapper} configured for Telegram's snake_case wire format, so the DTOs below stay plain
 * camelCase records with no annotations. Blocking and thread-confined to the caller's poll loop.
 */
public final class TelegramClient implements TelegramApi {
    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    // Must exceed the 30s long-poll (TelegramFrontend.POLL_TIMEOUT_SECONDS); tune via pijota.telegram.timeout /
    // PIJOTA_TELEGRAM_TIMEOUT.
    private static final Duration REQUEST_TIMEOUT = Duration
                                    .ofSeconds(Env.seconds("pijota.telegram.timeout", "PIJOTA_TELEGRAM_TIMEOUT", 60));

    // Telegram speaks snake_case and sends many fields we ignore; this mapper translates both ways and never throws on
    // unknown properties, so the records need no @JsonProperty plumbing.
    static final ObjectMapper TG = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final HttpClient http;
    private final String base;
    private final String fileBase;

    public TelegramClient(String token) {
        this(token, Proxies.httpClientBuilder()
                                        .connectTimeout(Duration.ofSeconds(Env.seconds(
                                                                        "pijota.telegram.connect-timeout",
                                                                        "PIJOTA_TELEGRAM_CONNECT_TIMEOUT", 10)))
                                        .build());
    }

    /** Injectable-client constructor — lets tests drive the API methods against a fake HTTP client. */
    TelegramClient(String token, HttpClient http) {
        this.http = http;
        this.base = "https://api.telegram.org/bot" + token;
        this.fileBase = "https://api.telegram.org/file/bot" + token; // downloads use a different host path than the API
    }

    /** Resolves a file_id to its bytes (getFile → download). Returns null on any failure so callers can degrade. */
    @Override
    public byte[] downloadFile(String fileId) {
        try {
            String path = call("getFile", new GetFileReq(fileId)).path("file_path").asText(null);
            if (path == null || path.isBlank())
                return null;
            var request = HttpRequest.newBuilder(URI.create(fileBase + "/" + path)).timeout(REQUEST_TIMEOUT).GET()
                                            .build();
            var resp = http.send(request, BodyHandlers.ofByteArray());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            log.warn("Failed to download Telegram file {}: {}", fileId, ErrorUtil.message(e));
            return null;
        }
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
        return call("sendMessage", new SendMessageReq(chatId, text, keyboard, null, null)).path("message_id").asLong();
    }

    /**
     * Sends an answer rendered as Telegram HTML ({@code parse_mode=HTML}); returns the new message id. An optional
     * inline keyboard (e.g. a Stop button) rides along. Link previews are suppressed so URL-heavy answers stay compact.
     */
    public long sendHtml(long chatId, String html, InlineKeyboard keyboard) {
        return call("sendMessage", new SendMessageReq(chatId, html, keyboard, "HTML", NO_PREVIEW)).path("message_id")
                                        .asLong();
    }

    /** Rewrites an already-sent message — used to "stream" by coalescing partial answers into one edited message. */
    public void editMessageText(long chatId, long messageId, String text) {
        call("editMessageText", new EditMessageReq(chatId, messageId, text, null, null, null));
    }

    /**
     * Rewrites a message, rendering the new body as Telegram HTML. The {@code keyboard} is replaced wholesale — pass
     * {@code null} to drop the inline keyboard (Telegram removes it when {@code reply_markup} is absent).
     */
    public void editHtml(long chatId, long messageId, String html, InlineKeyboard keyboard) {
        call("editMessageText", new EditMessageReq(chatId, messageId, html, "HTML", keyboard, NO_PREVIEW));
    }

    /** Sets the long description shown on the bot's profile / empty-chat screen. */
    public void setMyDescription(String description) {
        call("setMyDescription", new DescriptionReq(description));
    }

    /** Sets the short description shown under the bot's name. */
    public void setMyShortDescription(String shortDescription) {
        call("setMyShortDescription", new ShortDescriptionReq(shortDescription));
    }

    /** Registers the bot's command list so Telegram offers native {@code /} autocomplete. */
    public void setMyCommands(List<BotCommand> commands) {
        call("setMyCommands", new SetCommandsReq(commands));
    }

    /**
     * This bot's {@code @username} via {@code getMe} — for building a {@code https://t.me/<username>} link; null on
     * failure.
     */
    public String getMyUsername() {
        try {
            String username = call("getMe", Map.of()).path("username").asText(null);
            return username == null || username.isBlank() ? null : username;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Acknowledges a button tap so Telegram stops showing the loading spinner on the button. */
    public void answerCallback(String callbackQueryId) {
        call("answerCallbackQuery", new CallbackAnswerReq(callbackQueryId));
    }

    /** Shows a transient "typing…" indicator in the chat (clears itself after ~5s or on the next message). */
    public void sendChatAction(long chatId, String action) {
        call("sendChatAction", new ChatActionReq(chatId, action));
    }

    /** Uploads {@code content} as a file attachment (multipart). Returns the new message id. */
    public long sendDocument(long chatId, String filename, byte[] content, String caption) {
        var request = HttpRequest.newBuilder(URI.create(base + "/sendDocument")).timeout(REQUEST_TIMEOUT)
                                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(BOUNDARY, chatId,
                                                                        filename, content, caption)))
                                        .build();
        return dispatch("sendDocument", request).path("message_id").asLong();
    }

    // ---- transport ----

    // A fixed multipart boundary. ponytail: assumes uploaded content never contains this exact marker — true for the
    // Markdown/log payloads we attach; a content-derived boundary would be the upgrade if that ever stops holding.
    private static final String BOUNDARY = "----pijotaFormBoundary8x2KqzRm";

    private JsonNode call(String method, Object payload) {
        try {
            var request = HttpRequest.newBuilder(URI.create(base + "/" + method)).timeout(REQUEST_TIMEOUT)
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(TG.writeValueAsString(payload)))
                                            .build();
            return dispatch(method, request);
        } catch (JsonProcessingException e) {
            throw new TelegramException("telegram payload encode failed: " + method, e);
        }
    }

    private static final int MAX_RETRIES = 2;
    private static final long MAX_RETRY_SLEEP_MS = 30_000;

    private JsonNode dispatch(String method, HttpRequest request) {
        for (int attempt = 0;; attempt++) {
            JsonNode root = sendOnce(method, request);
            if (root.path("ok").asBoolean())
                return root.path("result");
            long retryAfter = retryAfterSeconds(root);
            if (retryAfter > 0 && attempt < MAX_RETRIES) {
                log.warn("telegram {} rate-limited; retrying in {}s", method, retryAfter);
                sleep(Math.min(retryAfter * 1000, MAX_RETRY_SLEEP_MS));
                continue;
            }
            throw new TelegramException(root.path("description").asText("telegram error"));
        }
    }

    private JsonNode sendOnce(String method, HttpRequest request) {
        try {
            return TG.readTree(http.send(request, BodyHandlers.ofString()).body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("telegram call interrupted: " + method, e);
        } catch (IOException e) {
            throw new TelegramException("telegram call failed: " + method + ": " + e.getMessage(), e);
        }
    }

    /** The {@code retry_after} seconds Telegram asks for on a 429 response, or 0 if this isn't a rate-limit error. */
    static long retryAfterSeconds(JsonNode root) {
        if (root.path("error_code").asInt() != 429)
            return 0;
        return Math.max(0, root.path("parameters").path("retry_after").asLong(0));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Builds a {@code multipart/form-data} body carrying {@code chat_id}, an optional caption, and the file part. */
    static byte[] multipartBody(String boundary, long chatId, String filename, byte[] content, String caption) {
        var out = new ByteArrayOutputStream();
        writeField(out, boundary, "chat_id", Long.toString(chatId));
        if (caption != null && !caption.isBlank())
            writeField(out, boundary, "caption", caption);
        writeBytes(out, "--" + boundary + "\r\n");
        writeBytes(out, "Content-Disposition: form-data; name=\"document\"; filename=\"" + safeFilename(filename)
                                        + "\"\r\n");
        writeBytes(out, "Content-Type: application/octet-stream\r\n\r\n");
        out.writeBytes(content);
        writeBytes(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value) {
        writeBytes(out, "--" + boundary + "\r\n");
        writeBytes(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeBytes(out, value);
        writeBytes(out, "\r\n");
    }

    private static void writeBytes(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    static String safeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "file" : filename;
        name = name.replaceAll("[\\r\\n\\\"]", "_").replaceAll("[/\\\\]", "_");
        return name.isBlank() ? "file" : name;
    }

    static List<Update> updatesFrom(JsonNode resultArray) {
        return List.of(TG.convertValue(resultArray, Update[].class));
    }

    // ---- incoming DTOs (only the fields a frontend needs; the lenient mapper drops the rest) ----

    public record Update(long updateId, TgMessage message, CallbackQuery callbackQuery) {
    }

    public record TgMessage(long messageId, TgChat chat, String text, String caption, List<PhotoSize> photo,
                                    TgFile document, TgFile audio, TgFile voice, TgFile video) {
        /** Compat constructor for text-only messages (and tests). */
        public TgMessage(long messageId, TgChat chat, String text) {
            this(messageId, chat, text, null, null, null, null, null, null);
        }

        /** The first attached non-photo file (document / audio / voice / video), or null. */
        public TgFile file() {
            return document != null ? document : video != null ? video : audio != null ? audio : voice;
        }
    }

    /** One size of a Telegram photo; the API sends an ascending list, so the last entry is the highest resolution. */
    public record PhotoSize(String fileId, int width, int height, long fileSize) {
    }

    /** A non-photo file (document / audio / voice / video) carrying its own MIME type. */
    public record TgFile(String fileId, String fileName, String mimeType, long fileSize) {
    }

    public record TgChat(long id) {
    }

    public record CallbackQuery(String id, TgMessage message, String data) {
    }

    /** A bot command shown in Telegram's {@code /} menu. {@code command} is the bare word, no leading slash. */
    public record BotCommand(String command, String description) {
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

    private static final LinkPreviewOptions NO_PREVIEW = new LinkPreviewOptions(true);

    private record LinkPreviewOptions(boolean isDisabled) {
    }

    private record GetFileReq(String fileId) {
    }

    private record SendMessageReq(long chatId, String text, InlineKeyboard replyMarkup, String parseMode,
                                    LinkPreviewOptions linkPreviewOptions) {
    }

    private record EditMessageReq(long chatId, long messageId, String text, String parseMode,
                                    InlineKeyboard replyMarkup, LinkPreviewOptions linkPreviewOptions) {
    }

    private record SetCommandsReq(List<BotCommand> commands) {
    }

    private record DescriptionReq(String description) {
    }

    private record ShortDescriptionReq(String shortDescription) {
    }

    private record CallbackAnswerReq(String callbackQueryId) {
    }

    private record ChatActionReq(long chatId, String action) {
    }
}
