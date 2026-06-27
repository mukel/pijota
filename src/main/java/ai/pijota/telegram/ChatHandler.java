package ai.pijota.telegram;

/**
 * What a single chat does with incoming input. {@link TelegramChat} is the implementation; the seam keeps
 * {@link TelegramFrontend}'s routing testable without a real {@code PijotaSession}.
 */
public interface ChatHandler {
    void onText(String text);

    void onCallback(TelegramClient.CallbackQuery callback);

    /** A photo message (Telegram delivers an ascending list of sizes); {@code caption} may be null. */
    default void onPhoto(java.util.List<TelegramClient.PhotoSize> photo, String caption) {}

    /** A non-photo file message (document / audio / voice / video); {@code caption} may be null. */
    default void onDocument(TelegramClient.TgFile file, String caption) {}
}
