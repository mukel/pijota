package ai.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformTest {

    @Test
    void osChecksAreMutuallyExclusive() {
        assertFalse(Platform.isWindows() && Platform.isMac(), "a host is not both Windows and macOS");
    }

    @Test
    void defaultShellMatchesOs() {
        String shell = Platform.defaultShell();
        assertNotNull(shell);
        assertFalse(shell.isBlank());
        // Windows: a PATH-resolved bash (no absolute Unix path that wouldn't exist there); Unix: an absolute POSIX
        // shell.
        if (Platform.isWindows())
            assertEquals("bash", shell);
        else
            assertTrue(shell.startsWith("/"), "Unix default shell should be an absolute path: " + shell);
    }
}
