package org.pijota.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class PijotaHomeTest {

    /** The fallback, so the override has to be out of the way: the suite sets one for every test. */
    @Test
    void defaultDirStaysUnderUserHome() {
        String previous = System.getProperty("pijota.home");
        System.clearProperty("pijota.home");
        try {
            assertEquals(Path.of(System.getProperty("user.home"), ".pijota"), PijotaHome.dir());
        } finally {
            if (previous != null)
                System.setProperty("pijota.home", previous);
        }
    }

    @Test
    void systemPropertyOverridesHome(@TempDir Path tmp) {
        String previous = System.getProperty("pijota.home");
        System.setProperty("pijota.home", tmp.resolve("cfg").toString());
        try {
            assertEquals(tmp.resolve("cfg").toAbsolutePath().normalize(), PijotaHome.dir());
            assertEquals(tmp.resolve("cfg").resolve("sessions").toAbsolutePath().normalize(),
                                            PijotaHome.resolve("sessions"));
        } finally {
            if (previous == null)
                System.clearProperty("pijota.home");
            else
                System.setProperty("pijota.home", previous);
        }
    }

    /**
     * The home holds sessions/ (whole transcripts), history (every prompt typed) and logs/. Only settings.json and
     * auth.json set their own mode, and writing one happened to tighten the directory as a side effect. A user who
     * authenticates by environment variable, which is what the first-run message asks for, writes neither and so kept a
     * world-readable 755 home with all of that inside it.
     */
    @Test
    void ensurePrivateCreatesAnOwnerOnlyHome(@TempDir Path tmp) throws Exception {
        String previous = System.getProperty("pijota.home");
        Path home = tmp.resolve("cfg");
        System.setProperty("pijota.home", home.toString());
        try {
            assertFalse(Files.exists(home), "fixture must start with no home at all");

            assertEquals(home.toAbsolutePath().normalize(), PijotaHome.ensurePrivate());

            assertTrue(Files.isDirectory(home));
            var mode = Files.getPosixFilePermissions(home);
            assertEquals(PosixFilePermissions.fromString("rwx------"), mode,
                                            "another user must not be able to traverse into it: "
                                                                            + PosixFilePermissions.toString(mode));
        } finally {
            if (previous == null)
                System.clearProperty("pijota.home");
            else
                System.setProperty("pijota.home", previous);
        }
    }

    /** Called on every start, so it must be safe on a home that already exists and must not widen a tighter mode. */
    @Test
    void ensurePrivateIsIdempotent(@TempDir Path tmp) throws Exception {
        String previous = System.getProperty("pijota.home");
        Path home = tmp.resolve("cfg");
        System.setProperty("pijota.home", home.toString());
        try {
            PijotaHome.ensurePrivate();
            Files.writeString(home.resolve("marker"), "keep me");

            PijotaHome.ensurePrivate();

            assertEquals("keep me", Files.readString(home.resolve("marker")), "an existing home must not be replaced");
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(home));
        } finally {
            if (previous == null)
                System.clearProperty("pijota.home");
            else
                System.setProperty("pijota.home", previous);
        }
    }
}
