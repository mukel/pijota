package org.pijota.llm;

import java.util.Locale;
import java.util.Set;

/**
 * Input modalities a model can accept, mirroring langchain4j's {@code ContentType}. A file's MIME type maps to exactly
 * one modality (see {@link #fromMime}); text-like files (txt/markdown/csv/docx/…) are extracted to {@link #TEXT} and
 * inlined upstream, so only the binary modalities are sent as typed content parts.
 */
public enum Modality {
    TEXT, IMAGE, AUDIO, VIDEO, PDF;

    // The image formats the vision providers accept. This is a provider/model limitation, not ours: Anthropic and
    // OpenAI take only JPEG/PNG/GIF/WebP (Gemini also accepts HEIC/HEIF), so we send the cross-provider intersection
    // and reject the rest (HEIC, TIFF, BMP, …). Transcoding them would need a native HEVC/HEIF codec, incompatible
    // with the single static native-image build — so unsupported formats are rejected with a clear message instead.
    private static final Set<String> SUPPORTED_IMAGE_MIMES = Set.of("image/jpeg", "image/png", "image/gif",
                                    "image/webp");

    /** Whether this image MIME type is accepted by the vision providers (JPEG/PNG/GIF/WebP); see the note above. */
    public static boolean isSupportedImage(String mime) {
        return mime != null && SUPPORTED_IMAGE_MIMES.contains(mime.toLowerCase(Locale.ROOT));
    }

    public static Modality fromMime(String mime) {
        if (mime == null)
            return TEXT;
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/"))
            return IMAGE;
        if (m.startsWith("audio/"))
            return AUDIO;
        if (m.startsWith("video/"))
            return VIDEO;
        if (m.equals("application/pdf"))
            return PDF;
        return TEXT; // text/*, application/json, docx, etc. — extracted to text rather than sent as a content part
    }

    /** Parses a modality name as written in the catalog ({@code "image"}); null for unknown tokens. */
    public static Modality parse(String name) {
        if (name == null)
            return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
