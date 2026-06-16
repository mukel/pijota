package ai.pijota.telegram;

import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.PijotaSession;
import ai.pijota.messages.Message;
import ai.pijota.util.ErrorUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One Telegram chat bound to one {@link PijotaSession} — the chat <em>is</em> the conversation. Translates the agent's
 * {@link AgentEvent} stream into chat messages and feeds the chat's text/button input back into the session. The
 * agent's answer is streamed by editing a single message (throttled, since Telegram rate-limits edits) and then
 * finalized, splitting across messages if it exceeds Telegram's length cap.
 *
 * <p>
 * Threading: {@link #onText}/{@link #onCallback} run on the frontend's poll thread; {@link #onEvent} runs on the
 * agent's streaming thread. The few shared fields are {@code volatile}; a chat only has one turn in flight at a time.
 */
public final class TelegramChat implements ChatHandler {
    private static final int MAX_MESSAGE_LEN = 4096; // Telegram's hard limit
    private static final long EDIT_INTERVAL_MS = 1500; // don't edit the streamed message faster than this

    private final long chatId;
    private final PijotaSession session;
    private final TelegramApi tg;
    private final TelegramUiContext ui;

    private volatile Long streamMessageId; // the message we keep editing while the answer streams in
    private volatile String lastSentText = "";
    private volatile long lastEditAt;

    public TelegramChat(long chatId, PijotaSession session, TelegramApi tg) {
        this.chatId = chatId;
        this.session = session;
        this.tg = tg;
        this.ui = new TelegramUiContext(chatId, tg);
        session.addEventListener(this::onEvent);
    }

    public TelegramUiContext ui() {
        return ui;
    }

    /** A text message arrived in this chat. */
    @Override
    public void onText(String text) {
        if (ui.offerReply(text))
            return; // it was the answer to a pending prompt(), not a new turn
        if (session.isBusy()) {
            session.followUp(text); // queue it onto the running turn (mirrors the TUI)
            return;
        }
        tg.sendChatAction(chatId, "typing");
        streamMessageId = null;
        lastSentText = "";
        session.prompt(text).exceptionally(t -> {
            tg.sendMessage(chatId, "❌ " + ErrorUtil.message(t));
            return List.of();
        });
    }

    /** A button was tapped in this chat. */
    @Override
    public void onCallback(TelegramClient.CallbackQuery callback) {
        tg.answerCallback(callback.id());
        long messageId = callback.message() != null ? callback.message().messageId() : 0;
        ui.resolve(messageId, callback.data());
    }

    private void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.AssistantMessage(Message.Assistant message, var usage) ->
                renderAnswer(message.content(), usage != null);
            case AgentEvent.ToolStart ignored -> tg.sendChatAction(chatId, "typing"); // keep "typing…" alive
            case AgentEvent.ToolResult(Message.ToolResult result) -> {
                if (result.isError())
                    tg.sendMessage(chatId, "⚠️ " + result.toolName() + " failed");
            }
            case AgentEvent.Error(Throwable error) -> tg.sendMessage(chatId, "❌ " + ErrorUtil.message(error));
            default -> {
                // TurnStart/TurnEnd/Compaction/thinking deltas etc. have no chat representation.
            }
        }
    }

    private void renderAnswer(String content, boolean isFinal) {
        // Throttle BEFORE any string work: a streaming AssistantMessage fires per token with the full accumulated
        // content, so stripping/copying it every time would be O(n) per token. Only the ~1/EDIT_INTERVAL live edit (and
        // the single final render) does real work.
        long now = System.currentTimeMillis();
        if (!isFinal && now - lastEditAt < EDIT_INTERVAL_MS)
            return;

        String text = content == null ? "" : content.strip();
        if (text.isEmpty())
            return; // tool-call-only assistant messages carry no prose

        if (!isFinal) {
            String preview = text.length() > MAX_MESSAGE_LEN ? text.substring(0, MAX_MESSAGE_LEN) : text;
            if (preview.equals(lastSentText))
                return; // Telegram rejects an edit to identical text
            lastEditAt = now;
            sendOrEdit(preview);
            return;
        }

        var chunks = split(text);
        sendOrEdit(chunks.get(0)); // finalize the streamed message with the first chunk
        for (int i = 1; i < chunks.size(); i++)
            tg.sendMessage(chatId, chunks.get(i)); // overflow as fresh messages
        streamMessageId = null;
        lastSentText = "";
    }

    private void sendOrEdit(String text) {
        if (streamMessageId == null)
            streamMessageId = tg.sendMessage(chatId, text);
        else
            tg.editMessageText(chatId, streamMessageId, text);
        lastSentText = text;
    }

    private static List<String> split(String text) {
        if (text.length() <= MAX_MESSAGE_LEN)
            return List.of(text);
        var parts = new ArrayList<String>();
        for (int i = 0; i < text.length(); i += MAX_MESSAGE_LEN)
            parts.add(text.substring(i, Math.min(text.length(), i + MAX_MESSAGE_LEN)));
        return parts;
    }
}
