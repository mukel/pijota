package ai.pijota.telegram;

/**
 * What a single chat does with incoming input. {@link TelegramChat} is the implementation; the seam keeps
 * {@link TelegramFrontend}'s routing testable without a real {@code PijotaSession}.
 */
public interface ChatHandler {
    void onText(String text);

    void onCallback(TelegramClient.CallbackQuery callback);
}
