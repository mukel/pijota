package org.pijota.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Expands the {@code @file} arguments of a prompt, inlining each file's contents where it was mentioned. */
public final class FileProcessor {

    private FileProcessor() {}

    public static String processFileReferences(List<String> fileArgs, String prompt) {
        if (fileArgs.isEmpty() && (prompt == null || !prompt.contains("@"))) {
            return prompt != null ? prompt : "";
        }

        var sb = new StringBuilder();
        if (prompt != null && !prompt.isBlank()) {
            sb.append(prompt).append('\n');
        }

        for (String ref : fileArgs) {
            appendFileContent(sb, ref);
        }

        return sb.toString().stripTrailing();
    }

    private static void appendFileContent(StringBuilder sb, String pathStr) {
        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            sb.append("[File not found: ").append(pathStr).append("]\n");
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path).limit(100)) {
                sb.append("[Directory: ").append(pathStr).append("]\n");
                stream.sorted().forEach(f -> {
                    String name = f.getFileName().toString();
                    sb.append("  ").append(name).append(Files.isDirectory(f) ? "/" : "").append('\n');
                });
            } catch (IOException e) {
                sb.append("[Error reading directory: ").append(pathStr).append("]\n");
            }
            return;
        }

        try {
            long size = Files.size(path);
            if (size > 1024 * 1024) {
                sb.append("[File too large: ").append(pathStr).append(" (").append(size / 1024).append(" KB)]\n");
                return;
            }

            String content = Files.readString(path);
            if (content.length() > 50000) {
                content = content.substring(0, 50000) + "\n... (truncated)";
            }

            String lang = detectLanguage(pathStr);
            sb.append("\n--- ").append(path.getFileName()).append(" (").append(lang).append(") ---\n");
            sb.append(content).append('\n');
        } catch (IOException e) {
            sb.append("[Error reading file: ").append(pathStr).append(": ").append(e.getMessage()).append("]\n");
        }
    }

    private static String detectLanguage(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".java"))
            return "java";
        if (lower.endsWith(".py"))
            return "python";
        if (lower.endsWith(".js"))
            return "javascript";
        if (lower.endsWith(".ts"))
            return "typescript";
        if (lower.endsWith(".rs"))
            return "rust";
        if (lower.endsWith(".go"))
            return "go";
        if (lower.endsWith(".c") || lower.endsWith(".h"))
            return "c";
        if (lower.endsWith(".cpp") || lower.endsWith(".hpp"))
            return "cpp";
        if (lower.endsWith(".rb"))
            return "ruby";
        if (lower.endsWith(".sh") || lower.endsWith(".bash"))
            return "bash";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml"))
            return "yaml";
        if (lower.endsWith(".json"))
            return "json";
        if (lower.endsWith(".xml"))
            return "xml";
        if (lower.endsWith(".md"))
            return "markdown";
        if (lower.endsWith(".sql"))
            return "sql";
        if (lower.endsWith(".html"))
            return "html";
        if (lower.endsWith(".css"))
            return "css";
        if (lower.endsWith(".toml"))
            return "toml";
        return "text";
    }
}
