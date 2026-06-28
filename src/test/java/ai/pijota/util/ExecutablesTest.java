package ai.pijota.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExecutablesTest {

    @Test
    void findsACommonShellThatIsAlwaysOnPath() {
        // at least one of these is on PATH on every platform we run on (sh/bash on *nix, cmd on Windows)
        Path found = Executables.findAny(List.of("sh", "bash", "cmd", "cmd.exe"));
        assumeTrue(found != null, "no known shell on PATH");
        assertTrue(Files.isExecutable(found), "the resolved shell is executable: " + found);
        assertTrue(Executables.isAvailable(found.getFileName().toString().replaceFirst("\\.exe$", ""))
                                        || Executables.isAvailable(found.getFileName().toString()));
    }

    @Test
    void reportsAbsentToolsAsUnavailable() {
        assertFalse(Executables.isAvailable("definitely-not-a-real-binary-xyz-123"));
        assertNull(Executables.find("definitely-not-a-real-binary-xyz-123"));
        assertFalse(Executables.anyAvailable(List.of("nope-aaa", "nope-bbb")));
        assertNull(Executables.findAny(List.of("nope-aaa", "nope-bbb")));
    }

    @Test
    void handlesNullBlankAndMissingPath() {
        assertNull(Executables.find(null));
        assertNull(Executables.find(""));
        assertNull(Executables.find("   "));
        assertFalse(Executables.isAvailable(null));
    }

    @Test
    void acceptsAnExplicitExecutablePathDirectly(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("tool.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        assertNull(Executables.find(script.toString()), "a non-executable file path is rejected");

        if (script.toFile().setExecutable(true)) {
            assertEquals(script, Executables.find(script.toString()), "an executable file path is returned as-is");
            assertTrue(Executables.isAvailable(script.toString()));
        }
    }

    @Test
    void aDirectoryIsNotAnExecutable(@TempDir Path tmp) {
        // directories can carry the execute bit but must not be treated as runnable programs
        assertNull(Executables.find(tmp.toString()));
    }
}
