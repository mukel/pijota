package org.pijota;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Packages moved to {@code org.pijota}, but paths spelled as strings did not move with them: the compiler cannot see
 * inside {@code "ai/pijota/Main.class"}, so three of them survived the rename and nothing failed.
 *
 * <p>
 * They fail quietly by design. Two were classpath fallbacks that simply stopped matching, and the third told the
 * self-modifying agent to edit {@code src/main/java/ai/pijota/}, a directory that has not existed since the rename.
 */
class PackagePathsAreCurrentTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** The package as it appears in a path, derived rather than typed, so this test cannot go stale the same way. */
    private static final String PACKAGE_PATH = Main.class.getPackageName().replace('.', '/');

    @Test
    void noSourceNamesAPackagePathThatNoLongerExists() throws IOException {
        var stale = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String[] lines = Files.readString(file).split("\\R");
                for (int i = 0; i < lines.length; i++)
                    if (lines[i].contains("ai/pijota") || lines[i].contains("ai.pijota"))
                        stale.add(SOURCES.relativize(file) + ":" + (i + 1) + "  " + lines[i].strip());
            }
        }
        assertTrue(stale.isEmpty(), "these name the pre-rename package, which no compiler checks: " + stale
                                        + "\nthe package is now " + PACKAGE_PATH);
    }

    /** The path the rename left behind really is gone, so the test above is guarding something real. */
    @Test
    void theCurrentPackagePathIsTheOneOnDisk() {
        assertEquals("org/pijota", PACKAGE_PATH);
        assertTrue(Files.isDirectory(SOURCES.resolve(PACKAGE_PATH)), "sources live at " + PACKAGE_PATH);
        assertFalse(Files.exists(SOURCES.resolve("ai/pijota")), "the old location must not come back");
    }
}
