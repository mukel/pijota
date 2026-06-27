package ai.pijota.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTextTest {

    @Test
    void plainTextIsDecoded() {
        assertEquals("hello\nworld", DocumentText.extract("text/plain", "a.txt", "hello\nworld".getBytes(UTF_8)));
    }

    @Test
    void codeWithUnknownMimeStillDecodes() {
        assertEquals("fn main() {}", DocumentText.extract("application/octet-stream", "a.rs",
                                        "fn main() {}".getBytes(UTF_8)));
    }

    @Test
    void nativeMediaReturnsNull() {
        assertNull(DocumentText.extract("image/png", "a.png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}));
        assertNull(DocumentText.extract("application/pdf", "a.pdf", "%PDF-1.7".getBytes(UTF_8)));
    }

    @Test
    void undecodableBinaryReturnsNull() {
        assertNull(DocumentText.extract("application/octet-stream", "a.bin", new byte[] {0, 1, 2, 0, 3, 0, 4}));
    }

    @Test
    void docxBodyTextIsExtracted() throws Exception {
        byte[] docx = docx("<w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t>World</w:t></w:r></w:p>");
        String out = DocumentText.extract(null, "report.docx", docx);
        assertNotNull(out);
        assertTrue(out.contains("Hello") && out.contains("World"), out);
    }

    @Test
    void pptxSlidesExtractInNumericOrder() throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bos)) {
            // add entries in lexical order (slide1, slide10, slide2) to prove they're re-sorted numerically
            for (String[] s : new String[][] {{"slide1", "One"}, {"slide10", "Ten"}, {"slide2", "Two"}}) {
                zip.putNextEntry(new ZipEntry("ppt/slides/" + s[0] + ".xml"));
                zip.write(("<p:sld><a:p><a:t>" + s[1] + "</a:t></a:p></p:sld>").getBytes(UTF_8));
                zip.closeEntry();
            }
        }
        String out = DocumentText.extract(null, "deck.pptx", bos.toByteArray());
        assertNotNull(out);
        assertTrue(out.indexOf("One") < out.indexOf("Two") && out.indexOf("Two") < out.indexOf("Ten"), out);
    }

    @Test
    void xlsxSharedStringsAreExtracted() throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write("<sst><si><t>Revenue</t></si><si><t>Q1</t></si></sst>".getBytes(UTF_8));
            zip.closeEntry();
        }
        String out = DocumentText.extract(null, "book.xlsx", bos.toByteArray());
        assertNotNull(out);
        assertTrue(out.contains("Revenue") && out.contains("Q1"), out);
    }

    @Test
    void ooxmlIsRecognizedByMimeWithoutExtension() throws Exception {
        byte[] docx = docx("<w:p><w:r><w:t>ByMime</w:t></w:r></w:p>");
        String out = DocumentText.extract("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "attachment", docx);
        assertNotNull(out);
        assertTrue(out.contains("ByMime"), out);
    }

    @Test
    void emptyOoxmlReturnsNull() throws Exception {
        assertNull(DocumentText.extract(null, "empty.docx", docx("<w:p></w:p>")));
    }

    @Test
    void emptyBytesReturnNull() {
        assertNull(DocumentText.extract("text/plain", "x.txt", new byte[0]));
        assertNull(DocumentText.extract("text/plain", "x.txt", null));
    }

    private static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

    private static byte[] docx(String body) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\"?><w:document><w:body>" + body + "</w:body></w:document>")
                                            .getBytes(UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
