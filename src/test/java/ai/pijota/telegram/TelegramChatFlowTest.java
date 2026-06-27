package ai.pijota.telegram;

import ai.pijota.agent.TestSession;
import ai.pijota.testkit.Await;
import ai.pijota.testkit.FauxLlm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link TelegramChat}'s message handlers end-to-end against a real {@link ai.pijota.agent.PijotaSession} (via
 * {@link TestSession}) and a recording {@link FakeTelegramApi}: slash commands are answered locally, and a plain
 * message runs a real agent turn whose streamed answer is delivered back through the bot API. The pure formatting
 * helpers are covered separately by {@code TelegramChatTest}; this covers the {@code onText} flow itself.
 */
class TelegramChatFlowTest {

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
        ts.llm().provider("deepseek").model("deepseek-v4-pro"); // text-only model — can't read PDFs natively
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
    void aTurnStartsWithATypingAction() {
        var c = newChat();
        c.session().llm().enqueue(FauxLlm.Response.contentWithUsage("done"));

        c.chat().onText("ping");

        Await.until(() -> c.api().actions().contains("typing"), Duration.ofSeconds(5),
                                        "a typing indicator should precede the reply");
    }
}
