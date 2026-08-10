package org.pijota.telegram;

import org.pijota.agent.TestSession;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.extensions.ExtensionRegistry;
import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;
import org.pijota.modes.SlashCommand;
import org.pijota.testkit.Await;
import org.pijota.testkit.FauxLlm;
import org.pijota.tools.SlashCommandTool;
import org.pijota.tools.Tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link TelegramChat}'s message handlers end-to-end against a real {@link org.pijota.agent.PijotaSession} (via
 * {@link TestSession}) and a recording {@link FakeTelegramApi}: slash commands are answered locally, and a plain
 * message runs a real agent turn whose streamed answer is delivered back through the bot API. The pure formatting
 * helpers are covered separately by {@code TelegramChatTest}; this covers the {@code onText} flow itself.
 */
class TelegramChatFlowTest {

    /** setTui installs a process-wide question handler; forks are reused, so it has to be given back. */
    @AfterEach
    void releaseTheQuestionHandler() {
        SlashCommandTool.setQuestionHandler(null);
    }

    @TempDir
    Path tempDir;

    private static final long CHAT_ID = 4242L;

    private record Chat(TelegramChat chat, FakeTelegramApi api, TestSession session) {
    }

    private Chat newChat() {
        var ts = TestSession.in(tempDir).build();
        var api = new FakeTelegramApi();
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, null);
        return new Chat(chat, api, ts);
    }

    /**
     * An extension command whose whole reply is {@code runtime().setStatus(...)}, which is how /skills, /goal and /mcp
     * answer. Wired exactly as {@code Main} wires the Telegram frontend, so the reply has to come back as a message.
     */
    @Test
    void anExtensionCommandThatAnswersViaStatusReachesTheChat() {
        var registry = new ExtensionRegistry();
        var ts = TestSession.in(tempDir).extensions(registry).build();
        var api = new FakeTelegramApi();
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, null);
        registry.setTui(chat.ui()); // Main.java does this for the Telegram frontend
        registry.api().registerSlashCommand(new SlashCommand("skilldemo", "List available skills", List.of(),
                                        _args -> registry.api().runtime().setStatus("explore · Search a codebase")));

        chat.onText("/skilldemo");

        assertTrue(api.allText().stream().anyMatch(t -> t.contains("explore · Search a codebase")),
                                        "the command's reply must reach the chat, not vanish: " + api.allText());
    }

    @Test
    void stopWhenIdleSaysNothingIsRunning() {
        var c = newChat();
        c.chat().onText("/stop");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.contains("Nothing")), c.api().allText().toString());
    }

    @Test
    void undoWithNothingToUndoIsReported() {
        var c = newChat();
        c.chat().onText("/undo");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.toLowerCase().contains("nothing to undo")),
                                        c.api().allText().toString());
    }

    @Test
    void usageReportsTokensInAndOut() {
        var c = newChat();
        c.chat().onText("/usage");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.contains("in") && t.contains("out")),
                                        c.api().allText().toString());
    }

    @Test
    void statusReportsModelSessionStateAndCwd() {
        var c = newChat();
        c.chat().onText("/status");
        String status = c.api().sent().getLast();
        assertTrue(status.contains("status: idle"), status);
        assertTrue(status.contains("model:"), status);
        assertTrue(status.contains("session:"), status);
        assertTrue(status.contains("cwd:"), status);
    }

    @Test
    void nameSetsThenShowsTheConversationName() {
        var c = newChat();
        c.chat().onText("/name my project chat");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.contains("my project chat")),
                                        c.api().allText().toString());

        var c2 = newChat();
        c2.chat().onText("/name");
        assertTrue(c2.api().allText().stream().anyMatch(t -> t.toLowerCase().contains("no name")),
                                        c2.api().allText().toString());
    }

    @Test
    void modelOpensAPickerKeyboard() {
        var c = newChat();
        c.chat().onText("/model");
        assertTrue(c.api().sent().stream().anyMatch(t -> t.contains("unavailable")), c.api().sent().toString());
    }

    @Test
    void modelOpensAPickerKeyboardWhenSettingsAreAvailable() {
        var ts = TestSession.in(tempDir).build();
        var api = new FakeTelegramApi();
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, settings);

        chat.onText("/model");

        assertTrue(api.keyboards().stream().anyMatch(k -> k != null), "an inline picker keyboard is shown");
    }

    @Test
    void thinkOpensAReasoningEffortPicker() {
        var c = newChat();
        c.chat().onText("/think");
        assertTrue(c.api().keyboards().stream().anyMatch(k -> k != null), "a reasoning-effort picker is shown");
    }

    @Test
    void newAsksForConfirmationBeforeClearing() {
        var c = newChat();
        c.chat().onText("/new");
        assertTrue(c.api().keyboards().stream().anyMatch(k -> k != null), "a Yes/No confirmation is shown");
    }

    @Test
    void undoAfterACompletedTurnRemovesIt() {
        var c = newChat();
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage("answer"));
        c.chat().onText("remember this");
        Await.until(() -> !c.session().session().isBusy(), Duration.ofSeconds(5), "turn finished");

        c.chat().onText("/undo");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.toLowerCase().contains("undone")),
                                        c.api().allText().toString());
    }

    @Test
    void exportSendsATranscriptDocumentAfterATurn() {
        var c = newChat();
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage("answer"));
        c.chat().onText("a question");
        Await.until(() -> !c.session().session().isBusy(), Duration.ofSeconds(5), "turn finished");

        c.chat().onText("/export");
        Await.until(() -> !c.api().documents().isEmpty(), Duration.ofSeconds(5), "a transcript file is sent");
        assertTrue(c.api().documents().get(0).filename().endsWith(".md"), c.api().documents().toString());
    }

    @Test
    void tappingAModelInThePickerSwitchesTheModel() {
        var ts = TestSession.in(tempDir).build();
        var api = new FakeTelegramApi();
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, settings);

        chat.onText("/model"); // sends the picker as message id 100 with a model keyboard
        chat.onCallback(new TelegramClient.CallbackQuery("cb",
                                        new TelegramClient.TgMessage(100, new TelegramClient.TgChat(CHAT_ID), null),
                                        "0")); // tap the first model

        assertTrue(api.allText().stream().anyMatch(t -> t.contains("switched to")), api.allText().toString());
    }

    @Test
    void aFileExceedingTheSizeCapIsRejected() {
        var c = newChat();
        c.api().onDownload(new byte[5 * 1024 * 1024 + 1]); // > Attachment.MAX_BYTES
        c.chat().onDocument(new TelegramClient.TgFile("fid", "big.txt", "text/plain", 5 * 1024 * 1024 + 1), "");
        Await.until(() -> c.api().allText().stream().anyMatch(t -> t.toLowerCase().contains("too large")),
                                        Duration.ofSeconds(5), "an oversized file is refused: " + c.api().allText());
    }

    @Test
    void aFailedDownloadIsReported() {
        var c = newChat(); // no onDownload stub → downloadFile returns null
        c.chat().onDocument(new TelegramClient.TgFile("fid", "x.txt", "text/plain", 10), "");
        Await.until(() -> c.api().allText().stream().anyMatch(t -> t.contains("Couldn't download")),
                                        Duration.ofSeconds(5), "a failed download is reported: " + c.api().allText());
    }

    @Test
    void helpCommandIsAnsweredLocallyAndNotForwardedToTheAgent() {
        var c = newChat();
        c.chat().onText("/help");

        assertFalse(c.api().sent().isEmpty(), "/help should produce a reply");
        assertTrue(c.session().session().userMessages().isEmpty(), "/help must not become an agent turn");
    }

    @Test
    void unknownCommandIsAnsweredWithHelpNotForwarded() {
        var c = newChat();
        c.chat().onText("/bogus");

        assertTrue(c.api().sent().stream().anyMatch(s -> s.contains("Unknown command")), c.api().sent().toString());
        assertTrue(c.session().session().userMessages().isEmpty());
    }

    @Test
    void plainMessageRunsAnAgentTurnAndStreamsTheAnswerBack() {
        var c = newChat();
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage("the telegram answer"));

        c.chat().onText("hello bot");

        Await.until(() -> c.api().allText().stream().anyMatch(t -> t.contains("the telegram answer")),
                                        Duration.ofSeconds(5), "the streamed answer was never delivered to the bot");
        assertEquals(1, c.session().session().userMessages().size());
        assertEquals("hello bot", c.session().session().userMessages().getFirst().content());
    }

    @Test
    void startSendsAWelcomeAndTheCommandList() {
        var c = newChat();
        c.chat().onText("/start");

        var last = c.api().sent().getLast();
        assertTrue(last.contains("pijota"), last); // a welcome line, not just a bare command dump
        assertTrue(last.contains("/model"), last); // followed by the command list
    }

    @Test
    void helpListsTheNativeCommands() {
        var c = newChat();
        c.chat().onText("/help");

        var last = c.api().sent().getLast();
        assertTrue(last.contains("/think"), last);
        assertTrue(last.contains("/model"), last);
    }

    @Test
    void messageDuringARunningTurnIsAcknowledgedAsQueued() {
        var c = newChat();
        c.session().llm().blockUntilAborted();
        c.chat().onText("first"); // starts a turn that blocks
        Await.until(() -> c.session().session().isBusy(), Duration.ofSeconds(5), "the turn should be running");

        c.chat().onText("second while busy"); // must be acknowledged, not silently swallowed

        assertTrue(c.api().sent().stream().anyMatch(s -> s.contains("Queued")), c.api().sent().toString());
        c.session().session().abort();
    }

    @Test
    void unsupportedImageDocumentIsRejectedWithAClearReply() {
        var c = newChat();
        c.chat().onDocument(new TelegramClient.TgFile("fid", "p.heic", "image/heic", 100), "look");

        assertTrue(c.api().sent().stream().anyMatch(s -> s.contains("isn't supported")), c.api().sent().toString());
        assertTrue(c.session().session().userMessages().isEmpty(), "rejected before it could become a turn");
    }

    @Test
    void supportedImageDocumentIsDownloadedAndSubmittedAsAttachment() {
        var c = newChat();
        c.api().onDownload(new byte[] {1, 2, 3});
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage("I see it"));

        c.chat().onDocument(new TelegramClient.TgFile("fid", "pic.png", "image/png", 3), "what is this");

        Await.until(() -> !c.session().session().userMessages().isEmpty(), Duration.ofSeconds(5),
                                        "the image was never submitted as a turn");
        var u = c.session().session().userMessages().getFirst();
        assertEquals(1, u.attachments().size());
        assertEquals("image/png", u.attachments().getFirst().mime());
    }

    @Test
    void photoIsDownloadedAndSubmittedAsJpeg() {
        var c = newChat();
        c.api().onDownload(new byte[] {9, 9, 9});
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage("nice photo"));

        c.chat().onPhoto(List.of(new TelegramClient.PhotoSize("small", 1, 1, 9),
                                        new TelegramClient.PhotoSize("big", 9, 9, 3)), "caption");

        Await.until(() -> !c.session().session().userMessages().isEmpty(), Duration.ofSeconds(5),
                                        "the photo was never submitted");
        var u = c.session().session().userMessages().getFirst();
        assertEquals(1, u.attachments().size());
        assertEquals("image/jpeg", u.attachments().getFirst().mime());
    }

    @Test
    void pdfDocumentIsAcceptedAndStagedOnANonPdfModel() {
        var ts = TestSession.in(tempDir).build();
        ts.llm().provider("deepseek").model("deepseek-v4-pro"); // text-only model, can't read PDFs natively
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("ok"));
        var api = new FakeTelegramApi();
        api.onDownload("%PDF-1.7 hello".getBytes());
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, null);

        chat.onDocument(new TelegramClient.TgFile("fid", "spec.pdf", "application/pdf", 13), "read it");

        Await.until(() -> !ts.session().userMessages().isEmpty(), Duration.ofSeconds(5), "the PDF was never submitted");
        assertFalse(api.sent().stream().anyMatch(s -> s.contains("can't read")), api.sent().toString());
        assertTrue(ts.session().userMessages().getFirst().content().contains("web_pdf_to_markdown"),
                                        ts.session().userMessages().getFirst().content());
    }

    @Test
    void audioFileIsSubmittedAsAttachmentOnAnAudioCapableModel() {
        var ts = TestSession.in(tempDir).build();
        ts.llm().provider("google").model("gemini-3.1-pro-preview"); // declares "audio" in the catalog
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("heard it"));
        var api = new FakeTelegramApi();
        api.onDownload(new byte[] {1, 2, 3});
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, null);

        chat.onDocument(new TelegramClient.TgFile("fid", "clip.mp3", "audio/mpeg", 3), "transcribe");

        Await.until(() -> !ts.session().userMessages().isEmpty(), Duration.ofSeconds(5), "audio was never submitted");
        var u = ts.session().userMessages().getFirst();
        assertEquals(1, u.attachments().size());
        assertEquals("audio/mpeg", u.attachments().getFirst().mime());
        assertFalse(api.sent().stream().anyMatch(s -> s.contains("can't read")), api.sent().toString());
        // The message appearing in history is not the end of the work: downloadAndSubmit runs the whole turn on the
        // common pool, and that turn keeps writing session files under tempDir. Returning here left JUnit deleting
        // the directory underneath a live write, which is the "Failed to close extension context" this test threw
        // intermittently under full-suite load and never in isolation.
        Await.until(() -> !ts.session().isBusy(), Duration.ofSeconds(5), "the turn was still running at teardown");
    }

    @Test
    void voiceMessageIsRejectedOnATextOnlyModel() {
        var ts = TestSession.in(tempDir).build();
        ts.llm().provider("deepseek").model("deepseek-v4-pro"); // text-only, audio is disabled for it
        var api = new FakeTelegramApi();
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, null);

        // a Telegram voice message has no filename + mime audio/ogg; it routes through file() → onDocument
        chat.onDocument(new TelegramClient.TgFile("vid", null, "audio/ogg", 100), null);

        Await.until(() -> api.sent().stream().anyMatch(s -> s.contains("can't read")), Duration.ofSeconds(5),
                                        "expected a 'can't read audio' rejection");
        assertTrue(api.sent().stream().anyMatch(s -> s.toLowerCase().contains("audio")), api.sent().toString());
        assertTrue(ts.session().userMessages().isEmpty(), "no turn should run for unsupported audio");
    }

    @Test
    void streamingShowsAStopButtonWhileGeneratingThenFinalizesWithoutItPlusAFooter() {
        var c = newChat();
        c.session().llm().enqueue(FauxLlm.Response.content(new Usage(10, 5), "Hello", " world"));

        c.chat().onText("hi");

        Await.until(() -> c.api().allText().stream().anyMatch(t -> t.contains("Hello world")), Duration.ofSeconds(5),
                                        "the streamed answer never arrived");
        var kbs = c.api().keyboards();
        assertTrue(kbs.stream().anyMatch(k -> k != null), "a Stop button is shown while streaming");
        assertNull(kbs.get(kbs.size() - 1), "the Stop button is dropped once the turn finalizes");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.contains("tok")),
                                        "a usage footer is appended at the end");
        // The footer reports tokens only. Cost rides under every single answer where it cannot be dismissed, so it
        // belongs to /usage, which is asked for. Guard it here: this is the one place the footer reaches a reader.
        assertTrue(c.api().allText().stream().noneMatch(t -> t.contains("$")),
                                        "no cost in the answer footer, /usage reports spend on request: "
                                                                        + c.api().allText());
    }

    @Test
    void tappingTheStopButtonAbortsTheTurnAndDropsTheButton() {
        var c = newChat();
        c.session().llm().blockUntilAborted();
        c.chat().onText("go"); // starts a turn that blocks
        Await.until(() -> c.session().session().isBusy(), Duration.ofSeconds(5), "the turn should be running");

        var msg = new TelegramClient.TgMessage(500, new TelegramClient.TgChat(CHAT_ID), null);
        c.chat().onCallback(new TelegramClient.CallbackQuery("cb1", msg, TelegramChat.ABORT_DATA));

        Await.until(() -> !c.session().session().isBusy(), Duration.ofSeconds(5), "the Stop tap should abort the turn");
        assertTrue(c.api().answeredCallbacks().contains("cb1"), "the tap is acknowledged");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.contains("Stopped")), c.api().allText().toString());
        assertNull(c.api().keyboards().get(c.api().keyboards().size() - 1), "the Stop button is removed");
    }

    @Test
    void aLongAnswerIsDeliveredAsAFileAttachment() {
        var c = newChat();
        String big = "line\n".repeat(2200); // ~11k chars, above the attach threshold (2× the message cap)
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage(big));

        c.chat().onText("dump the file");

        Await.until(() -> !c.api().documents().isEmpty(), Duration.ofSeconds(5), "the long answer was never attached");
        assertEquals(1, c.api().documents().size());
        assertTrue(c.api().documents().get(0).content().contains("line"), "the file holds the full answer");
        assertTrue(c.api().allText().stream().anyMatch(t -> t.contains("attached")), "an inline notice points to it");
    }

    @Test
    void aFileSentWhileATurnIsRunningIsRefusedNotPromptedOver() {
        var c = newChat();
        c.session().llm().blockUntilAborted();
        c.chat().onText("go"); // starts a blocking turn
        Await.until(() -> c.session().session().isBusy(), Duration.ofSeconds(5), "the turn should be running");
        int before = c.session().session().userMessages().size();

        c.chat().onDocument(new TelegramClient.TgFile("fid", "notes.txt", "text/plain", 4), "read this");

        assertTrue(c.api().sent().stream().anyMatch(s -> s.contains("Busy")), c.api().sent().toString());
        assertTrue(c.api().sent().stream().anyMatch(s -> s.contains("files are not")), c.api().sent().toString());
        assertEquals(before, c.session().session().userMessages().size(),
                                        "no new turn was started over the running one");
        c.session().session().abort();
    }

    @Test
    void aRunningToolShowsALiveHintCarryingAStopButton() {
        var ts = TestSession.in(tempDir).tool(echoTool()).build();
        var api = new FakeTelegramApi();
        var chat = new TelegramChat(CHAT_ID, ts.session(), api, null);
        ts.llm().enqueue(FauxLlm.Response.toolCall(new ToolCall("t1", "echo", "{\"text\":\"hi\"}")))
                                        .enqueue(FauxLlm.Response.contentWithUsage("all done"));

        chat.onText("use the tool");

        Await.until(() -> api.allText().stream().anyMatch(t -> t.contains("all done")), Duration.ofSeconds(5),
                                        "the turn never finished");
        assertTrue(api.allText().stream().anyMatch(t -> t.contains("echo")),
                                        "a running-tool hint is shown: " + api.allText());
        assertTrue(api.keyboards().stream().anyMatch(k -> k != null), "the tool hint carries a Stop button");
    }

    private static Tool echoTool() {
        return new Tool.ToolDefinition("echo", "Echoes input text", Map.of(),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool();
    }

    @Test
    void aTurnStartsWithATypingAction() {
        var c = newChat();
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage("done"));

        c.chat().onText("ping");

        Await.until(() -> c.api().actions().contains("typing"), Duration.ofSeconds(5),
                                        "a typing indicator should precede the reply");
    }
}
