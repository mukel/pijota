package ai.pijota.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PromptFileCompletionsTest {
    @TempDir
    Path tempDir;

    @Test
    void completesAbsoluteFileAndDirectoryPrefixes() throws Exception {
        Files.writeString(tempDir.resolve("Main.java"), "");
        Files.createDirectory(tempDir.resolve("models"));

        String prefix = tempDir.resolve("Ma").toString();
        var fileMatches = PromptFileCompletions.complete(prefix);

        assertTrue(fileMatches.stream().anyMatch(item -> item.name().endsWith("Main.java")));

        String dirPrefix = tempDir.resolve("mod").toString();
        var dirMatches = PromptFileCompletions.complete(dirPrefix);

        assertTrue(dirMatches.stream().anyMatch(item -> item.name().endsWith("models/")));
    }

    @Test
    void ignoresHiddenFiles() throws Exception {
        Files.writeString(tempDir.resolve(".secret"), "");

        var matches = PromptFileCompletions.complete(tempDir.resolve(".se").toString());

        assertTrue(matches.isEmpty());
    }
}
