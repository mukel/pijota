package ai.pijota.util;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts plain text from text-like files so their content can be inlined into a prompt — covering plain text / code
 * (decoded as UTF-8) and Office-XML documents (.docx/.pptx/.xlsx, unzipped dependency-free). Binary media that a model
 * reads natively (images, PDFs, audio, video) returns {@code null} — those are sent as typed attachments instead.
 */
public final class DocumentText {
    private DocumentText() {}

    private static final int MAX_CHARS = 200_000;

    /** Extracted text for a text-like file, or {@code null} for native media / undecodable binary. */
    public static String extract(String mime, String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return null;
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx") || name.endsWith(".pptx") || name.endsWith(".xlsx") || m.contains("openxmlformats"))
            return cap(extractOoxml(bytes));
        if (m.startsWith("image/") || m.startsWith("audio/") || m.startsWith("video/") || m.equals("application/pdf"))
            return null; // native media — not text
        return looksLikeText(bytes) ? cap(new String(bytes, StandardCharsets.UTF_8)) : null;
    }

    // Pull readable text out of an Office Open XML container (a ZIP): the Word body, PowerPoint slides, Excel strings.
    private static String extractOoxml(byte[] bytes) {
        var sb = new StringBuilder();
        var slides = new java.util.TreeMap<Integer, String>(); // collected separately so they emit in numeric order
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("word/document.xml") || n.equals("xl/sharedStrings.xml"))
                    sb.append(xmlToText(new String(zip.readAllBytes(), StandardCharsets.UTF_8))).append('\n');
                else if (n.startsWith("ppt/slides/slide") && n.endsWith(".xml"))
                    // ZIP entry order is lexical (slide10 before slide2); key by slide number for correct sequence
                    slides.put(slideNumber(n), xmlToText(new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
            // a malformed archive just yields whatever we read so far (possibly empty → null below)
        }
        slides.values().forEach(s -> sb.append(s).append('\n'));
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    private static int slideNumber(String entryName) {
        var digits = entryName.replaceAll("\\D+", ""); // e.g. ppt/slides/slide12.xml → "12"
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String xmlToText(String xml) {
        String s = xml.replaceAll("(?i)</w:p>|</a:p>|<w:br ?/>|</text:p>", "\n"); // paragraph / line breaks
        s = s.replaceAll("<[^>]+>", " "); // strip every remaining tag
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;",
                                        "'");
        return s.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    // Heuristic: a NUL byte or many control chars in the first 4KB means it's binary, not text/code.
    private static boolean looksLikeText(byte[] bytes) {
        int n = Math.min(bytes.length, 4096), suspicious = 0;
        for (int i = 0; i < n; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0)
                return false;
            if (b < 0x09 || (b > 0x0d && b < 0x20))
                suspicious++;
        }
        return suspicious * 100 / Math.max(1, n) < 5;
    }

    private static String cap(String s) {
        if (s == null)
            return null;
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) + "\n... (truncated)" : s;
    }
}
