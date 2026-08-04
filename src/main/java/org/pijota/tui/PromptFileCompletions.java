package org.pijota.tui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class PromptFileCompletions {
    private PromptFileCompletions() {}

    static List<CompletionItem> complete(String text) {
        int lastSpace = text.lastIndexOf(' ');
        String token = lastSpace >= 0 ? text.substring(lastSpace + 1) : text;
        // A leading @ marks a file reference: strip it for filesystem resolution, then re-add it to each completion so
        // the reference survives (otherwise "@/usr/lib" resolves "@/usr/" as a relative dir and finds nothing).
        // Bare-path
        // tab-completion (no @) keeps working.
        boolean atRef = token.startsWith("@");
        String prefix = atRef ? token.substring(1) : token;
        if (token.isEmpty() || (!atRef && prefix.isBlank()))
            return List.of();
        String at = atRef ? "@" : "";
        try {
            Path dir;
            String filePrefix;
            int lastSep = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf(File.separatorChar));
            if (lastSep >= 0) {
                String dirStr = prefix.substring(0, lastSep + 1);
                dir = Path.of(dirStr);
                if (!dir.isAbsolute())
                    dir = Path.of("").toAbsolutePath().resolve(dir);
                filePrefix = prefix.substring(lastSep + 1);
            } else {
                dir = Path.of("").toAbsolutePath();
                filePrefix = prefix;
            }
            if (!Files.isDirectory(dir))
                return List.of();
            String fPrefix = filePrefix.toLowerCase();
            try (var stream = Files.list(dir)) {
                return stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return !name.startsWith(".") && name.toLowerCase().contains(fPrefix);
                }).sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).limit(15).map(p -> {
                    String name = p.getFileName().toString();
                    // A file name is somebody else's bytes too, and on Linux it may hold an ESC. The agent creates
                    // files from archives, downloads and generated output, so the listing is not a trusted source.
                    String display = TerminalText.printable(at
                                                    + (lastSep >= 0 ? prefix.substring(0, lastSep + 1) + name : name));
                    if (Files.isDirectory(p))
                        display += "/";
                    return new CompletionItem(display, Files.isDirectory(p) ? "dir" : "");
                }).toList();
            } catch (IOException e) {
                return List.of();
            }
        } catch (Exception e) {
            return List.of();
        }
    }
}
