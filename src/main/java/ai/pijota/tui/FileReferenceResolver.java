package ai.pijota.tui;

import ai.pijota.modes.SyntaxHighlighter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

final class FileReferenceResolver {
    private final Path cwd;

    FileReferenceResolver(Path cwd) {
        this.cwd = cwd;
    }

    String expand(String text) {
        var result = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '@' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                int start = i;
                i++;
                StringBuilder ref = new StringBuilder();
                while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                    ref.append(text.charAt(i));
                    i++;
                }
                String path = ref.toString();
                if (path.contains("/") || path.contains("*")) {
                    String expanded = resolve(path);
                    result.append(expanded.isEmpty() ? text.substring(start, i) : expanded);
                } else {
                    result.append(text, start, i);
                }
            } else {
                result.append(text.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    private String resolve(String ref) {
        try {
            String raw = cwd.toString().replaceAll("\\(.*\\)", "").strip();
            if (raw.startsWith("~"))
                raw = System.getProperty("user.home") + raw.substring(1);
            Path base = Path.of(raw);
            if (ref.startsWith("~")) {
                String home = System.getProperty("user.home");
                ref = home != null ? home + ref.substring(1) : ref;
            }
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
