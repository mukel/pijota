package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.testkit.FauxLlm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link PijotaSession}'s attachment normalization end-to-end (the private {@code normalizeAttachments}
 * split): text-like files are extracted and inlined into the prompt, binary media is carried as attachments, and the
 * size / format / decode rejections each leave a visible note rather than dropping silently. Asserts on the stored
 * {@link Message.User} so the behavior is checked exactly as it reaches the model and the session file.
 */
class PijotaSessionAttachmentsTest {

    @TempDir
    Path tempDir;

    private Message.User runWith(String text, List<Message.Attachment> attachments) throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("ok"));
        ts.session().prompt(text, attachments).get(2, TimeUnit.SECONDS);
        return ts.session().userMessages().getFirst();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void textFileIsExtractedAndInlinedNotKeptAsMedia() throws Exception {
        var att = new Message.Attachment("text/plain", b64("hello from the file"), "note.txt");
        var u = runWith("read this", List.of(att));

        assertTrue(u.content().contains("<file name=\"note.txt\">"), u.content());
        assertTrue(u.content().contains("hello from the file"), u.content());
        assertTrue(u.attachments().isEmpty(), "text files are inlined, not carried as binary media");
    }

    @Test
    void imageIsKeptAsBinaryMedia() throws Exception {
        var att = new Message.Attachment("image/png", b64("\u0089PNG-ish"), "x.png");
        var u = runWith("look", List.of(att));

        assertEquals(1, u.attachments().size());
        assertEquals("image/png", u.attachments().getFirst().mime());
        assertEquals("look", u.content(), "media doesn't alter the prompt text");
    }

    @Test
    void oversizedMediaIsDroppedWithANote() throws Exception {
        // approxBytes = base64Length * 3/4 — 7M chars ≈ 5.25 MB, just over the 5 MB cap
        var att = new Message.Attachment("image/png", "A".repeat(7_000_000), "big.png");
        var u = runWith("big one", List.of(att));

        assertTrue(u.attachments().isEmpty(), "oversized media is not sent");
        assertTrue(u.content().contains("larger than 5 MB"), u.content());
    }

    @Test
    void unsupportedImageFormatIsDroppedWithANote() throws Exception {
        var att = new Message.Attachment("image/heic", b64("heic-bytes"), "p.heic");
        var u = runWith("from my phone", List.of(att));

        assertTrue(u.attachments().isEmpty(), "HEIC isn't accepted by the providers");
        assertTrue(u.content().contains("HEIC"), u.content());
        assertTrue(u.content().contains("JPEG/PNG/GIF/WebP"), u.content());
    }

    @Test
    void undecodableBinaryGivesAReadNote() throws Exception {
        var att = new Message.Attachment("application/octet-stream",
                                        Base64.getEncoder().encodeToString(new byte[] {0, 1, 2, 0, 3, 0}), "blob.bin");
        var u = runWith("what is this", List.of(att));

        assertTrue(u.attachments().isEmpty());
        assertTrue(u.content().contains("couldn't be read as text"), u.content());
    }

    @Test
    void textPromptSurvivesAlongsideADroppedAttachment() throws Exception {
        var att = new Message.Attachment("image/heic", b64("x"), "p.heic");
        var u = runWith("please describe it", List.of(att));

        assertTrue(u.content().startsWith("please describe it"), "the typed text is never lost");
    }

    @Test
    void pdfOnAModelThatCannotReadItIsStagedToAFileWithAToolNote() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().provider("deepseek").model("deepseek-v4-pro"); // a text-only catalog model (no pdf modality)
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("ok"));
        var pdf = new Message.Attachment("application/pdf", b64("%PDF-1.7 hello"), "spec.pdf");

        ts.session().prompt("read it", List.of(pdf)).get(2, TimeUnit.SECONDS);
        var u = ts.session().userMessages().getFirst();

        assertTrue(u.attachments().isEmpty(), "a staged PDF is not also sent as base64 media");
        assertTrue(u.content().contains("web_pdf_to_markdown"), u.content());
        assertTrue(u.content().contains(".pijota/uploads"), u.content());
        assertTrue(Files.exists(tempDir.resolve(".pijota/uploads/spec.pdf")), "PDF written to disk");
    }

    @Test
    void pdfOnAPdfCapableModelStaysANativeAttachment() throws Exception {
        // the faux model is unknown → modalities null → "allow all" → PDF kept as a native attachment, not staged
        var u = runWith("read it", List.of(new Message.Attachment("application/pdf", b64("%PDF"), "d.pdf")));

        assertEquals(1, u.attachments().size());
        assertEquals("application/pdf", u.attachments().getFirst().mime());
    }
}
