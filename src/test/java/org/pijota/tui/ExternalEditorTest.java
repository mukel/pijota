package org.pijota.tui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalEditorTest {

    @Test
    void editReturnsUpdatedContentAndDeletesTempDirectory() throws Exception {
        var seenFile = new AtomicReference<Path>();
        var editor = new ExternalEditor((shell, command) -> {
            Path file = Path.of(command.substring(command.lastIndexOf(' ') + 1).replace("'", ""));
            seenFile.set(file);
            Files.writeString(file, "changed\n", StandardCharsets.UTF_8);
            return 0;
        });

        var result = editor.edit("/bin/sh", "fake-editor", "initial");

        assertTrue(result.changed());
        assertEquals("changed", result.content());
        assertFalse(Files.exists(seenFile.get().getParent()));
    }

    @Test
    void nonZeroExitKeepsInitialContent() throws Exception {
        var editor = new ExternalEditor((shell, command) -> 2);

        var result = editor.edit("/bin/sh", "fake-editor", "initial");

        assertFalse(result.changed());
        assertEquals(2, result.exitCode());
        assertEquals("initial", result.content());
    }

    @Test
    void editWritesNullInitialContentAsEmptyFile() throws Exception {
        var editor = new ExternalEditor((shell, command) -> {
            Path file = Path.of(command.substring(command.lastIndexOf(' ') + 1).replace("'", ""));
            assertEquals("", Files.readString(file));
            Files.writeString(file, "filled", StandardCharsets.UTF_8);
            return 0;
        });

        var result = editor.edit("/bin/sh", "fake-editor", null);

        assertTrue(result.changed());
        assertEquals("filled", result.content());
    }

    @Test
    void editPassesShellAndQuotedPromptPathToLauncher() throws Exception {
        var seenShell = new AtomicReference<String>();
        var seenCommand = new AtomicReference<String>();
        var editor = new ExternalEditor((shell, command) -> {
            seenShell.set(shell);
            seenCommand.set(command);
            return 0;
        });

        editor.edit("/bin/custom-sh", "code --wait", "initial");

        assertEquals("/bin/custom-sh", seenShell.get());
        assertTrue(seenCommand.get().startsWith("code --wait "), seenCommand.get());
        assertTrue(seenCommand.get().contains("prompt.md"), seenCommand.get());
    }

    @Test
    void editDeletesTempDirectoryWhenLauncherThrows() {
        var seenFile = new AtomicReference<Path>();
        var editor = new ExternalEditor((shell, command) -> {
            Path file = Path.of(command.substring(command.lastIndexOf(' ') + 1).replace("'", ""));
            seenFile.set(file);
            throw new java.io.IOException("launch failed");
        });

        var ex = assertThrows(java.io.IOException.class, () -> editor.edit("/bin/sh", "fake-editor", "initial"));

        assertEquals("launch failed", ex.getMessage());
        assertFalse(Files.exists(seenFile.get().getParent()));
    }
}
