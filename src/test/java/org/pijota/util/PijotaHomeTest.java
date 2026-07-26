package org.pijota.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PijotaHomeTest {

    @Test
    void defaultDirStaysUnderUserHome() {
        assertEquals(Path.of(System.getProperty("user.home"), ".pijota"), PijotaHome.dir());
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
}
