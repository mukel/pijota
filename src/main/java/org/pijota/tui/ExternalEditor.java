package org.pijota.tui;

import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class ExternalEditor {
    private final ProcessLauncher launcher;

    ExternalEditor() {
        this((shell, command) -> ProcessRunner.command(shell, Platform.shellCommandFlag(shell), command)
                                        .runInteractive());
    }

    ExternalEditor(ProcessLauncher launcher) {
        this.launcher = launcher;
    }

    Result edit(String shell, String editorCommand, String initialContent) throws IOException {
        Path dir = Files.createTempDirectory("pijota-editor-");
        Path file = dir.resolve("prompt.md");
        try {
            Files.writeString(file, initialContent != null ? initialContent : "", StandardCharsets.UTF_8);
            int code = launcher.launch(shell, editorCommand + " " + Util.shellQuote(file.toString()));
            if (code != 0)
                return new Result(false, code, initialContent);
            return new Result(true, code, Files.readString(file, StandardCharsets.UTF_8).stripTrailing());
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir))
            return;
        try (var stream = Files.walk(dir)) {
            var paths = stream.sorted(Comparator.reverseOrder()).toList();
            IOException first = null;
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    if (first == null)
                        first = e;
                }
            }
            if (first != null)
                throw first;
        }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        int launch(String shell, String command) throws IOException;
    }

    record Result(boolean changed, int exitCode, String content) {
    }
}
