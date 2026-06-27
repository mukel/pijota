package ai.pijota.tui;

import ai.pijota.messages.Message;
import ai.pijota.modes.SyntaxHighlighter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

final class FileReferenceResolver {
    private final Path cwd;

    FileReferenceResolver(Path cwd) {
        this.cwd = cwd;
    }

    /** The result of expanding {@code @}-references: inlined text plus any image files pulled out as attachments. */
    record Expansion(String text, List<Message.Attachment> attachments) {
    }

    Expansion expand(String text) {
        var attachments = new ArrayList<Message.Attachment>();
        var result = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '@' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                int start = i;
                i++;
                // @"…" / @'…' allow paths with spaces (e.g. from fzf completion). Double quotes honor \" and \\
                // escapes;
                // single quotes are literal. An unquoted reference runs to the next whitespace, as before.
                char quote = i < text.length() && (text.charAt(i) == '"' || text.charAt(i) == '\'')
                                                ? text.charAt(i)
                                                : 0;
                StringBuilder ref = new StringBuilder();
                if (quote != 0) {
                    i++; // past the opening quote
                    while (i < text.length() && text.charAt(i) != quote) {
                        if (quote == '"' && text.charAt(i) == '\\' && i + 1 < text.length())
                            i++; // \" or \\ → take the next char literally
                        ref.append(text.charAt(i));
                        i++;
                    }
                    if (i < text.length())
                        i++; // past the closing quote
                } else {
                    while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                        ref.append(text.charAt(i));
                        i++;
                    }
                }
                String path = ref.toString();
                if (quote != 0 || path.contains("/") || path.contains("*")) {
                    if (mediaMime(path) != null && !path.contains("*")) {
                        // a recognized media / office-doc file → attach it; if too large or unreadable, say so rather
                        // than falling through to a (failed) text inline that would leave the raw @path in the prompt
                        Message.Attachment media = mediaAttachment(path);
                        if (media != null) {
                            attachments.add(media);
                            result.append("[").append(media.filename()).append("]");
                        } else {
                            result.append("[").append(path).append(" — too large or unreadable]");
                        }
                    } else if (unsupportedImageLabel(path) != null && !path.contains("*")) {
                        // a recognized image format the providers can't read (HEIC/TIFF/BMP) — say so, don't inline
                        // bytes
                        result.append("[").append(path).append(" — ").append(unsupportedImageLabel(path))
                                                        .append(" images aren't supported; use JPEG/PNG/GIF/WebP]");
                    } else {
                        String expanded = resolve(path);
                        result.append(expanded.isEmpty() ? text.substring(start, i) : expanded);
                    }
                } else {
                    result.append(text, start, i);
                }
            } else {
                result.append(text.charAt(i));
                i++;
            }
        }
        return new Expansion(result.toString(), attachments);
    }

    /**
     * Formats a path as an {@code @}-reference for the input buffer (e.g. fzf completion), round-tripping through
     * {@link #expand}'s quote-aware parser. A slash-less name gets a {@code ./} so it parses as a path rather than a
     * bare {@code @word} (which the resolver leaves literal). The result is double-quoted — with {@code \\} and
     * {@code "} escaped (apostrophes need none inside double quotes) — when it contains whitespace or starts with a
     * quote; otherwise it's emitted bare. An inner {@code @} is harmless (only a boundary {@code @} triggers parsing).
     */
    static String reference(String path) {
        String p = path.contains("/") || path.contains("*") ? path : "./" + path;
        boolean quote = p.isEmpty() || p.charAt(0) == '"' || p.charAt(0) == '\''
                                        || p.chars().anyMatch(Character::isWhitespace);
        return quote ? "@\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : "@" + p;
    }

    /**
     * Resolves {@code ref} to a base64 attachment for media / office documents (image, pdf, audio, video, docx, …), or
     * {@code null} if it isn't a recognized (small enough) media/doc file — those stay on the inline-text path.
     */
    private Message.Attachment mediaAttachment(String ref) {
        if (ref.contains("*") || ref.contains("?"))
            return null; // globs stay on the text path
        String mime = mediaMime(ref);
        if (mime == null)
            return null;
        try {
            Path file = resolveBase().resolve(stripHome(ref)).normalize();
            if (!Files.isRegularFile(file) || Files.size(file) > Message.Attachment.MAX_BYTES)
                return null;
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
            return new Message.Attachment(mime, base64, file.getFileName().toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    // Media & office-doc extensions sent as attachments (image/pdf/audio/video gate on the model; docx/xlsx/pptx are
    // extracted to text downstream). Plain text / code returns null here and is inlined instead.
    private static String mediaMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (n.substring(n.lastIndexOf('.') + 1)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "oga" -> "audio/ogg";
            case "m4a", "aac" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> null;
        };
    }

    // Image formats the providers can't read (a provider/model limitation — transcoding needs a native codec). Returns
    // a
    // short label for the message; null for anything else (which falls through to the normal text-inline path).
    private static String unsupportedImageLabel(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".heic") || n.endsWith(".heif"))
            return "HEIC";
        if (n.endsWith(".tif") || n.endsWith(".tiff"))
            return "TIFF";
        if (n.endsWith(".bmp"))
            return "BMP";
        return null;
    }

    private Path resolveBase() {
        String raw = cwd.toString().replaceAll("\\(.*\\)", "").strip();
        if (raw.startsWith("~"))
            raw = System.getProperty("user.home") + raw.substring(1);
        return Path.of(raw);
    }

    private static String stripHome(String ref) {
        if (ref.startsWith("~")) {
            String home = System.getProperty("user.home");
            return home != null ? home + ref.substring(1) : ref;
        }
        return ref;
    }

    private String resolve(String ref) {
        try {
            Path base = resolveBase();
            ref = stripHome(ref);
            if (ref.contains("*") || ref.contains("?")) {
                var matches = new ArrayList<Path>();
                Path globBase = base;
                String pattern = ref;
                int slash = Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\'));
                if (slash >= 0) {
                    globBase = base.resolve(ref.substring(0, slash));
                    pattern = ref.substring(slash + 1);
                }
                try (var stream = Files.newDirectoryStream(globBase, pattern)) {
                    for (Path p : stream)
                        matches.add(p);
                } catch (Exception ignored) {}
                if (matches.isEmpty())
                    return "";
                matches.sort(null);
                var sb = new StringBuilder();
                for (Path p : matches) {
                    sb.append('\n');
                    sb.append(p).append(":\n```").append(
                                                    SyntaxHighlighter.languageForFilename(p.getFileName().toString()))
                                                    .append('\n');
                    sb.append(Files.readString(p)).append('\n');
                    sb.append("```\n");
                }
                return sb.toString();
            }
            Path file = base.resolve(ref).normalize();
            if (!Files.isRegularFile(file))
                return "";
            String content = Files.readString(file);
            if (content.length() > 32_000)
                content = content.substring(0, 32_000) + "\n... (truncated)";
            return "\n" + file + ":\n```" + SyntaxHighlighter.languageForFilename(file.getFileName().toString()) + "\n"
                                            + content + "\n```\n";
        } catch (Exception ignored) {}
        return "";
    }
}
