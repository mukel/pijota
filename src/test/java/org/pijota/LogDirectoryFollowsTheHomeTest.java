package org.pijota;

import org.pijota.util.PijotaHome;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Logback substitutes {@code ${...}} when it parses its config, and it only knows {@code ${user.home}}. Hard-coding the
 * path there meant {@code --verbose} wrote to the real {@code ~/.pijota/logs} however the home was redirected, while
 * every other file moved. The help text that names the log's location was wrong in that case.
 *
 * <p>
 * Nothing compiles logback.xml, so the link between the property Main publishes and the one the config reads is two
 * strings that have to match.
 */
class LogDirectoryFollowsTheHomeTest {

    private static final String PROPERTY = "pijota.logDir";
    private static final Path CONFIG = Path.of("src/main/resources/logback.xml");

    @Test
    void theConfigTakesItsDirectoryFromTheResolvedHome() throws IOException {
        String config = Files.readString(CONFIG);

        assertTrue(config.contains("${" + PROPERTY), "logback.xml must read ${" + PROPERTY + "}: " + CONFIG);
        // Every sink, not just the current one: a rolled file left on ${user.home} splits the log across two homes.
        for (String line : config.split("\\R")) {
            if (!line.contains("pijota.log") && !line.contains("pijota.%d"))
                continue;
            assertTrue(line.contains("${" + PROPERTY),
                                            "this sink still resolves against the wrong home: " + line.strip());
        }
    }

    @Test
    void mainPublishesThePropertyTheConfigReads() throws ClassNotFoundException {
        Class.forName("org.pijota.Main"); // its static initializer sets the property before the first logger

        String logDir = System.getProperty(PROPERTY);
        assertNotNull(logDir, PROPERTY + " is never set, so logback falls back to the default home");
        assertEquals(PijotaHome.resolve("logs").toString(), logDir,
                                        "the published directory must be the one under the home in force");
    }
}
