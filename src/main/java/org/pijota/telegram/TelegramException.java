package org.pijota.telegram;

/** Thrown when a Telegram Bot API call fails (transport error or {@code "ok": false} response). */
public final class TelegramException extends RuntimeException {
    public TelegramException(String message) {
        super(message);
    }

    public TelegramException(String message, Throwable cause) {
        super(message, cause);
    }
}
