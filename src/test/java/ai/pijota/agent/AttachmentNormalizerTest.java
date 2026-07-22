package ai.pijota.agent;

import static org.junit.jupiter.api.Assertions.*;

import ai.pijota.llm.Modality;
import ai.pijota.messages.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentNormalizerTest {

    @TempDir
    Path cwd;

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes());
    }

    @Test
    void nullAndEmptyAttachmentsPassTextThrough() {
        assertEquals("hi", AttachmentNormalizer.normalize("hi", null, null, "/tmp").text());
        assertEquals("hi", AttachmentNormalizer.normalize("hi", List.of(), null, "/tmp").text());
        assertEquals("", AttachmentNormalizer.normalize(null, null, null, "/tmp").text());
    }

    @Test
    void textFileIsExtractedAndInlined() {
        var a = new Message.Attachment("text/plain", b64("file body"), "notes.txt");
        var result = AttachmentNormalizer.normalize("prompt", List.of(a), null, cwd.toString());
        assertTrue(result.text().contains("<file name=\"notes.txt\">"), result.text());
        assertTrue(result.text().contains("file body"), result.text());
        assertTrue(result.media().isEmpty(), "text files are inlined, not carried as media");
    }

    @Test
    void supportedImageIsCarriedAsMedia() {
        var img = new Message.Attachment("image/png", b64("pngbytes"), "pic.png");
        var result = AttachmentNormalizer.normalize("look", List.of(img), Set.of(Modality.IMAGE), cwd.toString());
        assertEquals(1, result.media().size());
        assertEquals("look", result.text(), "no note added for a supported image");
    }

    @Test
    void unsupportedImageFormatIsRejectedWithANote() {
        var heic = new Message.Attachment("image/heic", b64("heicbytes"), "photo.heic");
        var result = AttachmentNormalizer.normalize("", List.of(heic), Set.of(Modality.IMAGE), cwd.toString());
        assertTrue(result.media().isEmpty(), "unsupported image is not sent");
        assertTrue(result.text().contains("HEIC images aren't supported"), result.text());
    }

    @Test
    void oversizedMediaIsOmittedWithANote() {
        // approxBytes() = base64 length * 3/4, so the base64 must exceed MAX_BYTES * 4/3 to count as oversized.
        String huge = "x".repeat((int) (Message.Attachment.MAX_BYTES * 4 / 3) + 1_000);
        var big = new Message.Attachment("image/png", huge, "big.png");
        var result = AttachmentNormalizer.normalize("", List.of(big), Set.of(Modality.IMAGE), cwd.toString());
        assertTrue(result.media().isEmpty());
        assertTrue(result.text().contains("omitted — larger than 5 MB"), result.text());
    }

    @Test
    void pdfIsStagedToDiskWhenTheModelCannotReadPdfs() throws Exception {
        var pdf = new Message.Attachment("application/pdf", b64("%PDF-1.4 body"), "doc.pdf");
        var result = AttachmentNormalizer.normalize("", List.of(pdf), Set.of(Modality.IMAGE), cwd.toString());
        assertTrue(result.media().isEmpty(), "unreadable PDF is staged, not sent as media");
        assertTrue(result.text().contains("web_pdf_to_markdown"), result.text());
        try (var files = Files.walk(cwd.resolve(".pijota").resolve("uploads"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().equals("doc.pdf")), "PDF written to uploads dir");
        }
    }

    @Test
    void nativePdfSupportCarriesThePdfAsMedia() {
        var pdf = new Message.Attachment("application/pdf", b64("%PDF body"), "doc.pdf");
        var result = AttachmentNormalizer.normalize("", List.of(pdf), Set.of(Modality.PDF), cwd.toString());
        assertEquals(1, result.media().size(), "a model that reads PDFs natively gets it as a typed part");
    }
}
