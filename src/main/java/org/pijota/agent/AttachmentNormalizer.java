package org.pijota.agent;

import org.pijota.llm.Modality;
import org.pijota.messages.Message;
import org.pijota.util.DocumentText;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Turns a user's raw attachments into prompt text plus typed media parts. Text-like files (txt/code/docx/…) are
 * extracted and inlined into the prompt; binary media (image/pdf/audio/video) is carried as {@link Message.Attachment}s
 * and gated to the model's modalities at send time. Pure and stateless, it holds no session state, so it takes
 * everything it needs as parameters.
 */
public final class AttachmentNormalizer {
    private AttachmentNormalizer() {}

    /**
     * Prompt text (with inlined text files and omission notes) plus the binary media to send as typed content parts.
     */
    public record Result(String text, List<Message.Attachment> media) {
    }

    /**
     * Splits attachments: text-like files are extracted and appended to the prompt; binary media is kept as-is.
     * {@code supported} is the active model's input modalities ({@code null} = unknown → allow all); a PDF a model
     * can't read natively is staged to a file under {@code cwd} so it can be read with the {@code web_pdf_to_markdown}
     * tool.
     */
    public static Result normalize(String text, List<Message.Attachment> attachments, Set<Modality> supported,
                                    String cwd) {
        return normalize(text, attachments, supported, cwd, null, null);
    }

    public static Result normalize(String text, List<Message.Attachment> attachments, Set<Modality> supported,
                                    String cwd, String provider, String model) {
        if (attachments == null || attachments.isEmpty())
            return new Result(text == null ? "" : text, List.of());
        var media = new ArrayList<Message.Attachment>();
        var sb = new StringBuilder(text == null ? "" : text);
        for (var a : attachments) {
            String name = a.filename() != null && !a.filename().isBlank() ? a.filename() : "file";
            Modality mod = Modality.fromMime(a.mime());
            if (mod != Modality.TEXT) {
                // a PDF the model can't read natively → stage it to disk so it can be read with the web_pdf_to_markdown
                // tool
                if (mod == Modality.PDF && supported != null && !supported.contains(Modality.PDF)) {
                    String rel = stageFile(cwd, name, a.base64());
                    sb.append(rel != null
                                                    ? "\n\n[Attached PDF \"" + name + "\" saved to " + rel + ", "
                                                                                    + AttachmentMessages.modelLabel(provider,
                                                                                                                    model)
                                                                                    + " can't read PDFs "
                                                                                    + "directly; use the web_pdf_to_markdown tool to read it.]"
                                                    : "\n\n[attachment \"" + name + "\" couldn't be saved]");
                }
                // image formats the providers can't read (HEIC/TIFF/BMP/…); reject rather than let the API 400 later
                else if (mod == Modality.IMAGE && !Modality.isSupportedImage(a.mime()))
                    sb.append("\n\n[").append(AttachmentMessages.unsupportedImage(name, provider, model)).append(']');
                else if (a.approxBytes() <= Message.Attachment.MAX_BYTES) // core size floor, every frontend inherits
                                                                          // it
                    media.add(a); // image / pdf / audio / video → sent as a typed content part (gated by the model)
                else // never drop silently; leave a note so the model (and transcript) know it was omitted
                    sb.append("\n\n[attachment \"").append(name).append("\" omitted, larger than 5 MB]");
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(a.base64());
            } catch (RuntimeException e) {
                bytes = null;
            }
            String extracted = bytes == null ? null : DocumentText.extract(a.mime(), a.filename(), bytes);
            if (extracted != null && !extracted.isBlank())
                sb.append("\n\n<file name=\"").append(name).append("\">\n").append(extracted).append("\n</file>");
            else
                sb.append("\n\n[attachment \"").append(name).append("\" couldn't be read as text]");
        }
        return new Result(sb.toString(), media);
    }

    /**
     * Writes an uploaded file under {@code <cwd>/.pijota/uploads/} so a tool can read it; returns the relative path or
     * null.
     */
    private static String stageFile(String cwd, String name, String base64) {
        try {
            Path dir = Path.of(cwd, ".pijota", "uploads");
            Files.createDirectories(dir);
            Path file = dir.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_"));
            Files.write(file, Base64.getDecoder().decode(base64));
            return Path.of(cwd).relativize(file).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
