package ai.pijota.telegram;

import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionExporter;
import ai.pijota.config.SettingsManager;
import ai.pijota.llm.ModelInfo;
import ai.pijota.llm.Modality;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;
import ai.pijota.messages.Usage;
import ai.pijota.modes.SlashCommand;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TelegramChat.class);
    private static final int MAX_MESSAGE_LEN = 4096; // Telegram's hard limit
    private static final long EDIT_INTERVAL_MS = 1500; // don't edit the streamed message faster than this

    // A one-tap "Stop" button shown on the live message; its callback_data is matched in onCallback to abort the turn.
    static final String ABORT_DATA = "__abort__";
    private static final TelegramClient.InlineKeyboard STOP_BUTTON = TelegramClient.InlineKeyboard
                                    .row(TelegramClient.InlineKeyboard.button("⏹ Stop", ABORT_DATA));

    /** The bot's command menu — registered with Telegram for {@code /} autocomplete and the source of {@code /help}. */
    public static final List<TelegramClient.BotCommand> COMMANDS = List.of(
                                    new TelegramClient.BotCommand("stop", "Interrupt the running turn"),
                                    new TelegramClient.BotCommand("undo", "Undo the last message"),
                                    new TelegramClient.BotCommand("compact", "Summarize & shrink the context"),
                                    new TelegramClient.BotCommand("new", "Start a new conversation"),
                                    new TelegramClient.BotCommand("think", "Set reasoning effort"),
                                    new TelegramClient.BotCommand("usage", "Show token usage, cost & context"),
                                    new TelegramClient.BotCommand("model", "Switch the model"),
                                    new TelegramClient.BotCommand("export", "Export the transcript as a file"),
                                    new TelegramClient.BotCommand("name", "Name this conversation"),
                                    new TelegramClient.BotCommand("help", "Show this help"));

    /**
     * The bot's full {@code /} autocomplete menu: the built-in {@link #COMMANDS} plus extension-provided commands the
     * chat actually handles (via {@code runExtensionCommand}), so they're discoverable in Telegram — not just /help.
     * Telegram only allows {@code [a-z0-9_]{1,32}} names and caps the menu at 100, so hyphenated/duplicate names are
     * skipped rather than rejecting the whole call.
     */
    public static List<TelegramClient.BotCommand> menuCommands(List<SlashCommand> extensionCommands) {
        var menu = new ArrayList<>(COMMANDS);
        var seen = COMMANDS.stream().map(TelegramClient.BotCommand::command)
                                        .collect(Collectors.toCollection(java.util.HashSet::new));
        for (SlashCommand c : extensionCommands) {
            String name = c.name();
            if (name == null || !name.matches("[a-z0-9_]{1,32}") || !seen.add(name))
                continue;
            String desc = c.description() == null || c.description().isBlank() ? name : c.description();
            menu.add(new TelegramClient.BotCommand(name, desc.length() > 256 ? desc.substring(0, 256) : desc));
            if (menu.size() >= 100)
                break;
        }
        return menu;
    }

    private static final String WELCOME = "👋 I'm pijota — a coding agent you talk to in chat. Just type to ask "
                                    + "anything; I'll read/edit files, run commands and stream the answer back.\n\n";

    private final long chatId;
    private final PijotaSession session;
    private final TelegramApi tg;
    private final SettingsManager settings; // resolves API keys when /model switches the model
    private final TelegramUiContext ui;

    private volatile Long streamMessageId; // the message we keep editing while the answer streams in
    private volatile String lastSentText = "";
    private volatile long lastEditAt;
    private volatile boolean streamedProse; // true once real answer text (not a tool placeholder) has been shown

    public TelegramChat(long chatId, PijotaSession session, TelegramApi tg, SettingsManager settings) {
        this.chatId = chatId;
        this.session = session;
        this.tg = tg;
        this.settings = settings;
        this.ui = new TelegramUiContext(chatId, tg);
        session.addEventListener(this::onEvent);
    }

    public TelegramUiContext ui() {
        return ui;
    }

    @Override
    public void onText(String text) {
        if (ui.offerReply(text))
            return; // it was the answer to a pending prompt(), not a new turn
        if (text.strip().startsWith("/") && handleCommand(text.strip()))
            return; // a slash command (handled before busy/followUp so /stop can interrupt a running turn)
        if (session.isBusy()) {
            session.followUp(text); // queue it onto the running turn
            tg.sendMessage(chatId, "➕ Queued — I'll get to it after the current turn.");
            return;
        }
        runTurn(text, List.of());
    }

    /** Starts a turn: typing indicator, stream-state reset, prompt, and a shared error reply. */
    private void runTurn(String text, List<Message.Attachment> attachments) {
        tg.sendChatAction(chatId, "typing"); // "typing…" + the streamed reply are the progress signal
        streamMessageId = null;
        lastSentText = "";
        streamedProse = false;
        session.prompt(text, attachments).exceptionally(t -> {
            tg.sendMessage(chatId, "❌ " + ErrorUtil.message(t));
            return List.of();
        });
    }

    @Override
    public void onPhoto(java.util.List<TelegramClient.PhotoSize> photo, String caption) {
        // Telegram orders sizes ascending → the last is the highest resolution; photos are always JPEG.
        submitFile(photo.get(photo.size() - 1).fileId(), "image/jpeg", "photo.jpg", caption, "image");
    }

    @Override
    public void onDocument(TelegramClient.TgFile file, String caption) {
        String mime = file.mimeType() != null && !file.mimeType().isBlank()
                                        ? file.mimeType()
                                        : "application/octet-stream";
        String name = file.fileName() != null && !file.fileName().isBlank() ? file.fileName() : "file";
        submitFile(file.fileId(), mime, name, caption, "file");
    }

    /**
     * Downloads a Telegram file and submits it as an attachment, gated to the model's modalities (text always works).
     */
    private void submitFile(String fileId, String mime, String filename, String caption, String kind) {
        if (session.isBusy()) {
            tg.sendMessage(chatId, "➕ Busy right now — resend the " + kind + " once the current turn finishes.");
            return;
        }
        var modality = Modality.fromMime(mime);
        // text is extracted and PDFs are staged for the web_pdf_to_markdown tool, so both work on any model; other
        // media
        // (image/audio/video) needs the model to declare the modality.
        if (modality != Modality.TEXT && modality != Modality.PDF && !ModelRegistry.supports(session.llm().provider(),
                                        session.llm().model(), modality)) {
            tg.sendMessage(chatId, "⚠️ " + session.llm().model() + " can't read "
                                            + modality.name().toLowerCase(java.util.Locale.ROOT)
                                            + " files — switch with /model, then resend.");
            return;
        }
        if (modality == Modality.IMAGE && !Modality.isSupportedImage(mime)) { // provider limitation, not ours
            tg.sendMessage(chatId, "⚠️ That image format isn't supported by the model — send JPEG/PNG/GIF/WebP.");
            return;
        }
        // The download is multi-MB and blocking — run it off the single poll/dispatch thread so other chats aren't
        // stalled.
        java.util.concurrent.CompletableFuture.runAsync(() -> downloadAndSubmit(fileId, mime, filename, caption, kind));
    }

    private void downloadAndSubmit(String fileId, String mime, String filename, String caption, String kind) {
        byte[] bytes = tg.downloadFile(fileId);
        if (bytes == null) {
            tg.sendMessage(chatId, "❌ Couldn't download that " + kind + ".");
            return;
        }
        if (bytes.length > Message.Attachment.MAX_BYTES) {
            tg.sendMessage(chatId, "⚠️ That " + kind + " is too large (max 5MB).");
            return;
        }
        if (session.isBusy()) { // a turn may have started during the (async) download — don't prompt over it
            tg.sendMessage(chatId, "➕ Busy now — resend the " + kind + " once the current turn finishes.");
            return;
        }
        var attachment = new Message.Attachment(mime, java.util.Base64.getEncoder().encodeToString(bytes), filename);
        String text = caption != null && !caption.isBlank() ? caption : "(" + kind + ": " + filename + ")";
        runTurn(text, List.of(attachment));
    }

    /**
     * Handles a {@code /command}. Returns true if the text was a recognized command (or an unknown {@code /word}, which
     * we answer with help rather than feeding to the agent). Telegram appends {@code @botname} in groups, so we strip
     * it.
     */
    private boolean handleCommand(String text) {
        String cmd = commandWord(text);
        switch (cmd) {
            case "/start" -> {
                // Telegram deep link: t.me/<bot>?start=<payload> arrives as "/start <payload>" — a payload means the
                // user opened the bot from the remote-control link/QR, so confirm the connection.
                String payload = text.length() > cmd.length() ? text.substring(cmd.length()).strip() : "";
                String prefix = payload.isBlank() ? "" : "🔗 Connected to your pijota session.\n\n";
                tg.sendMessage(chatId, prefix + WELCOME + helpText());
            }
            case "/help" -> tg.sendMessage(chatId, helpText());
            case "/stop", "/cancel" -> {
                if (session.isBusy()) {
                    session.abort();
                    tg.sendMessage(chatId, "⏹ Stopped.");
                } else {
                    tg.sendMessage(chatId, "Nothing is running.");
                }
            }
            case "/undo" -> {
                if (requireIdle())
                    tg.sendMessage(chatId, session.undoLastUserTurn().map(s -> "↩️ Undone: " + s)
                                                    .orElse("Nothing to undo."));
            }
            case "/compact" -> {
                if (!requireIdle())
                    break;
                tg.sendChatAction(chatId, "typing");
                try {
                    session.compact("");
                    tg.sendMessage(chatId, "🗜 Context compacted.");
                } catch (RuntimeException e) {
                    tg.sendMessage(chatId, "❌ " + ErrorUtil.message(e));
                }
            }
            case "/new" -> {
                if (!requireIdle())
                    break;
                // Confirm first (parity with the TUI) — an accidental /new tap shouldn't drop the live conversation.
                ui.confirm("🆕 Start a new conversation? The current one is cleared (it stays saved on disk).")
                                                .thenAccept(yes -> {
                                                    if (!yes)
                                                        return;
                                                    try {
                                                        session.newSession(); // fresh session file; clears in-context
                                                                              // history
                                                        tg.sendMessage(chatId, "🆕 Started a new conversation.");
                                                    } catch (java.io.IOException e) {
                                                        tg.sendMessage(chatId, "❌ " + ErrorUtil.message(e));
                                                    }
                                                });
            }
            case "/usage" -> tg.sendMessage(chatId, usageSummary());
            case "/model" -> showModelPicker();
            case "/think" -> showThinkPicker();
            case "/export" -> exportTranscript();
            case "/name" -> nameConversation(commandArgs(text));
            default -> {
                if (!runExtensionCommand(cmd, text))
                    tg.sendMessage(chatId, "Unknown command " + cmd + ".\n\n" + helpText());
            }
        }
        return true;
    }

    /**
     * Falls an unmatched {@code /command} through to an extension-registered {@link SlashCommand} — the same registry
     * the TUI dispatches against — so {@code /plan}, {@code /goal}, {@code /btw}, {@code /mcp}, {@code /skills}… work
     * in chat too. Native commands above take precedence. Returns true if a command matched (failures are reported, not
     * thrown, since this runs on the poll thread).
     */
    private boolean runExtensionCommand(String cmd, String text) {
        String name = cmd.substring(1); // drop the leading "/"
        var match = session.extensionRegistry().slashCommands().stream().filter(c -> c.matches(name)).findFirst();
        if (match.isEmpty())
            return false;
        try {
            match.get().handler().accept(commandArgs(text));
        } catch (RuntimeException e) {
            tg.sendMessage(chatId, "❌ " + ErrorUtil.message(e));
        }
        return true;
    }

    /** The argument text after the command word, e.g. {@code "/btw what is X"} → {@code "what is X"}. */
    static String commandArgs(String text) {
        String t = text.strip();
        int sp = t.indexOf(' ');
        return sp < 0 ? "" : t.substring(sp + 1).strip();
    }

    /** A short {@code ": <why>"} suffix for a tool failure, or "" when the tool gave no detail. */
    static String reason(String content) {
        return content == null || content.isBlank() ? "" : ": " + clip(content, 200);
    }

    /** True if no turn is in flight; otherwise tells the user to {@code /stop} first and returns false. */
    private boolean requireIdle() {
        if (session.isBusy()) {
            tg.sendMessage(chatId, "A turn is running — /stop it first.");
            return false;
        }
        return true;
    }

    /** Normalizes a command message to its bare lowercase command, e.g. {@code "/Help@bot now"} → {@code "/help"}. */
    static String commandWord(String text) {
        String word = text.strip().split("\\s+", 2)[0];
        int at = word.indexOf('@'); // Telegram appends @botname to commands in group chats
        return (at >= 0 ? word.substring(0, at) : word).toLowerCase();
    }

    private String usageSummary() {
        String base = "📊 %s in · %s out · $%.4f".formatted(Util.fmtCount(session.totalInputTokens()),
                                        Util.fmtCount(session.totalOutputTokens()), session.totalCost());
        return base + contextLine(session.estimatedContextTokens(), contextWindow()) + budgetLine();
    }

    /** A "context X/Y (Z%)" line for {@code /usage}, or "" when the window is unknown. */
    static String contextLine(long used, long window) {
        if (window <= 0)
            return "";
        int pct = (int) Math.min(100, used * 100 / window);
        return "\n🧵 context %s/%s (%d%%)".formatted(Util.fmtCount(used), Util.fmtCount(window), pct);
    }

    private long contextWindow() {
        try {
            var m = session.latestModelSelection();
            return m == null ? 0 : m.contextWindow();
        } catch (Exception e) {
            return 0;
        }
    }

    private String budgetLine() {
        Double budget = session.costBudget();
        return budget == null ? "" : "\n💰 budget $%.2f (spent $%.4f)".formatted(budget, session.totalCost());
    }

    /** Picker for the model's reasoning effort; tapping a level applies it for the rest of the conversation. */
    private void showThinkPicker() {
        if (!requireIdle())
            return;
        ThinkingLevel current;
        try {
            current = session.latestThinkingLevel();
        } catch (Exception e) {
            current = null;
        }
        final ThinkingLevel cur = current;
        ui.select("🧠 Reasoning effort", List.of(ThinkingLevel.values()),
                                        l -> l == cur ? "✓ " + l.name().toLowerCase() : l.name().toLowerCase(), l -> {
                                            session.setThinkingLevel(l);
                                            tg.sendMessage(chatId, "🧠 reasoning: " + l.name().toLowerCase());
                                        });
    }

    private void exportTranscript() {
        var file = session.sessionFile();
        if (file == null) {
            tg.sendMessage(chatId, "No active session to export.");
            return;
        }
        try {
            String md = SessionExporter.toMarkdown(file);
            if (md == null || md.isBlank())
                tg.sendMessage(chatId, "Nothing to export yet.");
            else
                tg.sendDocument(chatId, "transcript.md", md.getBytes(StandardCharsets.UTF_8), "📄 transcript");
        } catch (Exception e) {
            tg.sendMessage(chatId, "❌ " + ErrorUtil.message(e));
        }
    }

    /** {@code /name <text>} sets the conversation's name; bare {@code /name} shows it. */
    private void nameConversation(String arg) {
        try {
            if (arg.isBlank()) {
                String name = session.sessionName();
                tg.sendMessage(chatId, name == null || name.isBlank()
                                                ? "This conversation has no name yet — /name <text>."
                                                : "🏷 " + name);
            } else {
                session.setSessionName(arg);
                tg.sendMessage(chatId, "🏷 named: " + arg);
            }
        } catch (java.io.IOException e) {
            tg.sendMessage(chatId, "❌ " + ErrorUtil.message(e));
        }
    }

    /** Current provider/model id, or {@code null} if it can't be read. */
    private String currentModelId() {
        try {
            var m = session.latestModelSelection();
            return m == null ? null : m.model();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Offers the models the configured API keys can actually run as an inline-keyboard picker; tapping one switches the
     * session's model. Refuses mid-turn, since switching the model under a running turn would corrupt it.
     */
    private void showModelPicker() {
        if (!requireIdle())
            return;
        String current = currentModelId();
        var models = ModelRegistry.availableModels(settings);
        if (models.isEmpty()) {
            tg.sendMessage(chatId, "No models available (no API keys configured).");
            return;
        }
        String title = current == null ? "🧠 Pick a model" : "🧠 Pick a model (current: " + current + ")";
        ui.select(title, models, m -> m.id().equals(current) ? "✓ " + m.displayName() : m.displayName(),
                                        this::switchTo);
    }

    private void switchTo(ModelInfo model) {
        var resolved = ModelRegistry.resolve(model.provider(), model.id(), settings);
        if (resolved == null) {
            tg.sendMessage(chatId, "❌ no API key for " + model.provider());
            return;
        }
        session.switchModel(resolved.provider(), resolved.model(), resolved.apiKey(), resolved.contextWindow());
        tg.sendMessage(chatId, "🧠 switched to " + resolved.provider() + "/" + resolved.model());
    }

    @Override
    public void onCallback(TelegramClient.CallbackQuery callback) {
        tg.answerCallback(callback.id());
        long messageId = callback.message() != null ? callback.message().messageId() : 0;
        if (ABORT_DATA.equals(callback.data())) {
            if (session.isBusy())
                session.abort();
            if (messageId != 0) // strip the Stop button so the interrupted message reads as finished
                editHtmlOrPlain(messageId, lastSentText.isEmpty() ? "⏹ Stopped." : lastSentText, null);
            streamMessageId = null;
            return;
        }
        ui.resolve(messageId, callback.data());
    }

    private void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.AssistantMessage(Message.Assistant message, var usage) ->
                renderAnswer(message.content(), usage);
            case AgentEvent.ToolStart start -> {
                tg.sendChatAction(chatId, "typing"); // keep "typing…" alive
                showToolActivity(toolHint(start.toolName(), start.argumentsSupplier().get()));
            }
            case AgentEvent.ToolResult(Message.ToolResult result) -> {
                if (result.isError())
                    tg.sendMessage(chatId, "⚠️ " + result.toolName() + " failed" + reason(result.content()));
            }
            case AgentEvent.Error(Throwable error) -> tg.sendMessage(chatId, "❌ " + ErrorUtil.message(error));
            default -> {
                // TurnStart/TurnEnd/Compaction/thinking deltas etc. have no chat representation.
            }
        }
    }

    private void renderAnswer(String content, Usage usage) {
        boolean isFinal = usage != null; // the agent attaches usage only to the finalized message
        // Throttle BEFORE any string work: a streaming AssistantMessage fires per token with the full accumulated
        // content, so stripping/copying it every time would be O(n) per token. Only the ~1/EDIT_INTERVAL live edit (and
        // the single final render) does real work.
        long now = System.currentTimeMillis();
        // Show the first prose immediately (no dead air while the typing bubble may already be expiring); throttle only
        // the subsequent live edits. lastSentText is empty until the first paint.
        if (!isFinal && !lastSentText.isEmpty() && now - lastEditAt < EDIT_INTERVAL_MS)
            return;

        String text = content == null ? "" : content.strip();
        if (text.isEmpty())
            return; // tool-call-only assistant messages carry no prose

        if (!isFinal) {
            String preview = text.length() > MAX_MESSAGE_LEN ? text.substring(0, MAX_MESSAGE_LEN) : text;
            if (preview.equals(lastSentText))
                return; // Telegram rejects an edit to identical text
            lastEditAt = now;
            streamedProse = true;
            sendOrEdit(preview, STOP_BUTTON); // keep the Stop button on the live message while it streams
            return;
        }

        streamedProse = true;
        if (shouldAttach(text)) {
            // Finalize the live message as a short preview + notice, and send the whole answer as a file — far nicer
            // than a wall of split messages for big diffs, file dumps and logs.
            sendOrEdit(previewOf(text) + "\n\n📄 full answer attached" + footer(usage), null);
            tg.sendDocument(chatId, answerFileName(), text.getBytes(StandardCharsets.UTF_8), null);
        } else {
            // Split the answer first (which closes any open code fence), then append the usage footer — so the footer
            // is always its own paragraph after the content, never swallowed into a trailing code block.
            var chunks = new ArrayList<>(split(text));
            appendFooter(chunks, footer(usage));
            sendOrEdit(chunks.get(0), null); // finalize: drop the Stop button — the turn is done
            for (int i = 1; i < chunks.size(); i++)
                sendChunk(chunks.get(i)); // overflow as fresh messages, each still HTML-rendered
        }
        streamMessageId = null;
        lastSentText = "";
    }

    // Above this many characters, an answer is delivered as a file attachment instead of split across chat messages.
    private static final int ATTACH_THRESHOLD = 2 * MAX_MESSAGE_LEN;

    static boolean shouldAttach(String text) {
        return text.length() > ATTACH_THRESHOLD;
    }

    /** The leading slice of a long answer, cut on a line boundary, shown inline next to the file attachment. */
    static String previewOf(String text) {
        int cap = 600;
        if (text.length() <= cap)
            return text;
        String head = text.substring(0, cap);
        int nl = head.lastIndexOf('\n');
        if (nl > cap / 2)
            head = head.substring(0, nl);
        return head.strip() + "\n\n…";
    }

    /**
     * Help text: the native command menu plus any extension slash commands that also work in chat (they're dispatched
     * via {@link #runExtensionCommand}, so listing them keeps the bot's real capabilities discoverable).
     */
    private String helpText() {
        var sb = new StringBuilder("Just type to chat. Commands:\n");
        for (var c : COMMANDS)
            sb.append("/").append(c.command()).append(" — ").append(c.description()).append('\n');
        var builtin = COMMANDS.stream().map(TelegramClient.BotCommand::command).collect(Collectors.toSet());
        var extras = session.extensionRegistry().slashCommands().stream().filter(c -> !builtin.contains(c.name()))
                                        .sorted(java.util.Comparator.comparing(SlashCommand::name)).toList();
        if (!extras.isEmpty()) {
            sb.append("\nMore (extensions):\n");
            for (var c : extras)
                sb.append("/").append(c.name()).append(" — ").append(c.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * A filename for the attached long answer, derived from the conversation name so saved files are distinguishable.
     */
    private String answerFileName() {
        try {
            String name = session.sessionName();
            if (name != null && !name.isBlank()) {
                String safe = name.strip().toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
                if (!safe.isBlank())
                    return safe + ".md";
            }
        } catch (Exception ignored) {} // naming is best-effort; fall back to a stable default
        return "answer.md";
    }

    /** Appends the footer to the last chunk, or as its own chunk if it wouldn't fit (keeps every chunk ≤ the cap). */
    static void appendFooter(List<String> chunks, String footer) {
        if (footer.isEmpty())
            return;
        int last = chunks.size() - 1;
        if (chunks.get(last).length() + footer.length() <= MAX_MESSAGE_LEN)
            chunks.set(last, chunks.get(last) + footer);
        else
            chunks.add(footer.strip());
    }

    /** A faint one-line usage footer appended to the final answer, or "" when there's nothing to report. */
    private String footer(Usage usage) {
        if (usage == null || usage.outputTokens() <= 0)
            return "";
        double cost = session.totalCost();
        String costPart = cost > 0 ? " · $%.4f".formatted(cost) : "";
        return "\n\n_%s tok%s_".formatted(Util.fmtCount(usage.outputTokens()), costPart);
    }

    /**
     * Shows the running tool as live feedback, but only until real answer prose appears — once the model is writing,
     * the streamed answer is the better signal and we never clobber it with a tool line. Reuses the stream message (the
     * placeholder simply becomes the answer when prose arrives) and honors the same edit throttle.
     */
    private void showToolActivity(String hint) {
        if (streamedProse)
            return;
        long now = System.currentTimeMillis();
        if (streamMessageId != null && now - lastEditAt < EDIT_INTERVAL_MS)
            return;
        lastEditAt = now;
        sendOrEdit(hint, STOP_BUTTON); // a tool is running → offer Stop
    }

    // Argument keys, in priority order, whose value best summarizes what a tool call is doing. "path" is last because
    // it's often just "." when a more specific key (e.g. grep's "pattern") is the interesting one.
    private static final List<String> HINT_KEYS = List.of("command", "file_path", "pattern", "query", "url", "path");

    /** A one-line "running" label for a tool call, e.g. {@code "💻 bash: npm test"} or {@code "📖 read: App.java"}. */
    static String toolHint(String toolName, String argsJson) {
        String emoji = switch (toolName) {
            case "bash" -> "💻";
            case "read" -> "📖";
            case "edit" -> "📝";
            case "write" -> "💾";
            case "grep" -> "🔍";
            case "glob", "ls" -> "📁";
            case "webfetch", "websearch" -> "🌐";
            default -> "🔧";
        };
        String arg = extractArg(argsJson);
        return arg.isEmpty() ? emoji + " " + toolName + "…" : emoji + " " + toolName + ": " + arg;
    }

    private static String extractArg(String argsJson) {
        if (argsJson == null || argsJson.isBlank())
            return "";
        try {
            JsonNode node = Util.MAPPER.readTree(argsJson);
            for (String key : HINT_KEYS) {
                JsonNode v = node.get(key);
                if (v != null && v.isTextual() && !v.asText().isBlank())
                    return clip(v.asText(), 80);
            }
        } catch (Exception ignored) {
            // not valid JSON (or no useful key) → no hint, just the tool name
        }
        return "";
    }

    /** One-line, ellipsized: strips, collapses newlines, then truncates to {@code max} chars with a "…". */
    private static String clip(String s, int max) {
        String t = s.strip().replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** Sends or edits the single streamed message as HTML (with an optional keyboard), then remembers the text. */
    private void sendOrEdit(String text, TelegramClient.InlineKeyboard keyboard) {
        if (streamMessageId == null) {
            streamMessageId = sendHtmlOrPlain(text, keyboard);
        } else {
            try {
                editHtmlOrPlain(streamMessageId, text, keyboard);
            } catch (RuntimeException e) {
                // the streamed message is gone (deleted / no longer editable) — recover by starting a fresh one,
                // otherwise the rest of the answer would stream into the void
                log.debug("telegram edit failed; sending a fresh message: {}", e.getMessage());
                streamMessageId = sendHtmlOrPlain(text, keyboard);
            }
        }
        lastSentText = text;
    }

    /** Sends one overflow chunk as its own message. */
    private void sendChunk(String text) {
        sendHtmlOrPlain(text, null);
    }

    // The HTML render can in principle be rejected by Telegram (CommonMark normally yields valid HTML, but a parse
    // error must never drop the answer). These two helpers send/edit as HTML and fall back to the raw, unformatted
    // text.

    private long sendHtmlOrPlain(String text, TelegramClient.InlineKeyboard keyboard) {
        try {
            return tg.sendHtml(chatId, TelegramHtml.toHtml(text), keyboard);
        } catch (TelegramException e) {
            log.debug("telegram HTML send failed, falling back to plain text: {}", e.getMessage());
            return tg.sendMessage(chatId, text, keyboard);
        }
    }

    private void editHtmlOrPlain(long messageId, String text, TelegramClient.InlineKeyboard keyboard) {
        try {
            tg.editHtml(chatId, messageId, TelegramHtml.toHtml(text), keyboard);
        } catch (TelegramException e) {
            log.debug("telegram HTML edit failed, falling back to plain text: {}", e.getMessage());
            tg.editMessageText(chatId, messageId, text);
        }
    }

    // Headroom under the hard limit for the fences we may inject. A chunk that ends mid-code reserves room for one
    // closing fence; a single over-long line inside code is wrapped in its OWN pair, so it reserves room for two.
    private static final int FENCE_RESERVE = 8;
    private static final int FENCED_LINE_RESERVE = 2 * FENCE_RESERVE;

    /**
     * Splits a Markdown answer into Telegram-sized chunks, breaking on line boundaries rather than mid-word and never
     * leaving a fenced code block open across a chunk: an open fence is closed at the end of one chunk and reopened at
     * the start of the next, so each chunk is valid Markdown that renders on its own. A single line longer than the
     * limit (e.g. a giant base64 blob) is hard-split as a last resort.
     */
    static List<String> split(String text) {
        if (text.length() <= MAX_MESSAGE_LEN)
            return List.of(text);
        var parts = new ArrayList<String>();
        var cur = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            // Hard-split a single line that can't fit even on its own (rare: a giant blob with no newlines).
            int lineLimit = MAX_MESSAGE_LEN - (inFence ? FENCED_LINE_RESERVE : 0);
            while (line.length() > lineLimit) {
                if (cur.length() > 0)
                    parts.add(flush(cur, inFence));
                parts.add(inFence ? "```\n" + line.substring(0, lineLimit) + "\n```" : line.substring(0, lineLimit));
                line = line.substring(lineLimit);
            }
            // Normal case: start a fresh chunk before this line would overflow the current one.
            int limit = MAX_MESSAGE_LEN - (inFence ? FENCE_RESERVE : 0);
            if (cur.length() > 0 && cur.length() + line.length() + 1 > limit) {
                parts.add(flush(cur, inFence));
                if (inFence)
                    cur.append("```\n"); // reopen the code block (language is dropped — acceptable)
            }
            cur.append(line).append('\n');
            if (isFence(line))
                inFence = !inFence;
        }
        if (cur.length() > 0)
            parts.add(flush(cur, inFence)); // close a still-open fence on the final chunk too
        return parts;
    }

    /** Returns the chunk's text, appending a closing fence when a code block is still open. */
    private static String flush(StringBuilder cur, boolean inFence) {
        if (inFence)
            cur.append("```");
        String chunk = cur.toString().stripTrailing();
        cur.setLength(0);
        return chunk;
    }

    private static boolean isFence(String line) {
        String t = line.strip();
        return t.startsWith("```") || t.startsWith("~~~");
    }
}
