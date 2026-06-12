package ai.pijota.tui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FileReferenceResolverTest {

    @Test
    void expandPlainTextUnchanged() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-frr");
        try {
            var resolver = new FileReferenceResolver(tmp);
            assertEquals("hello world", resolver.expand("hello world"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void expandAtWithoutPathLeftAlone() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-frr");
        try {
            var resolver = new FileReferenceResolver(tmp);
            assertEquals("see @Test annotation", resolver.expand("see @Test annotation"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void expandResolvesFileReference(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("example.txt");
        Files.writeString(file, "contents");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("check @./example.txt");

        assertTrue(expanded.contains("example.txt"), expanded);
        assertTrue(expanded.contains("contents"), expanded);
        assertTrue(expanded.contains("```"), expanded);
        assertFalse(expanded.contains("@./example.txt"), expanded);
    }

    @Test
    void expandMissingFileReturnsOriginalReference() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-frr");
        try {
            var resolver = new FileReferenceResolver(tmp);
            assertEquals("@missing.txt", resolver.expand("@missing.txt"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void expandAtSignInMiddleLeftAlone() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-frr");
        try {
            var resolver = new FileReferenceResolver(tmp);
            assertEquals("abc@foo bar", resolver.expand("abc@foo bar"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void expandMultipleReferences(@TempDir Path tmp) throws Exception {
        var file1 = tmp.resolve("a.txt");
        var file2 = tmp.resolve("b.txt");
        Files.writeString(file1, "alpha");
        Files.writeString(file2, "beta");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@./a.txt @./b.txt");

        assertTrue(expanded.contains("a.txt"));
        assertTrue(expanded.contains("alpha"));
        assertTrue(expanded.contains("b.txt"));
        assertTrue(expanded.contains("beta"));
    }

    @Test
    void expandDirectoryEntryNotExpanded(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve("sub"));

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@sub");
        assertEquals("@sub", expanded);
    }

    @Test
    void expandAtSignAtStartWithoutSlashLeftAlone(@TempDir Path tmp) throws Exception {
        var resolver = new FileReferenceResolver(tmp);
        assertEquals("@justword", resolver.expand("@justword"));
    }

    @Test
    void resolvesRelativeSubdirectoryFile(@TempDir Path tmp) throws Exception {
        var dir = tmp.resolve("sub");
        Files.createDirectory(dir);
        var file = dir.resolve("nested.txt");
        Files.writeString(file, "deep");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("see @./sub/nested.txt");

        assertTrue(expanded.contains("nested.txt"));
        assertTrue(expanded.contains("deep"));
    }

    @Test
    void globExpandsMultipleFiles(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "alpha");
        Files.writeString(tmp.resolve("b.txt"), "beta");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@./*.txt");

        assertTrue(expanded.contains("alpha"));
        assertTrue(expanded.contains("beta"));
    }

    @Test
    void globNoMatchesReturnsOriginalReference(@TempDir Path tmp) {
        var resolver = new FileReferenceResolver(tmp);
        assertEquals("@./*.nothing", resolver.expand("@./*.nothing"));
    }

    @Test
    void truncatesLargeFiles(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("big.txt");
        Files.writeString(file, "x".repeat(32_001));

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@./big.txt");

        assertTrue(expanded.contains("... (truncated)"));
    }

    @Test
    void referenceAtStartOfLine(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("note.txt");
        Files.writeString(file, "note");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@./note.txt end");

        assertTrue(expanded.contains("note"));
        assertFalse(expanded.contains("@./note.txt"));
    }

    @Test
    void resolvesTildeHomeReference(@TempDir Path tmp) throws Exception {
        var home = tmp.resolve("home");
        Files.createDirectory(home);
        var file = home.resolve("doc.md");
        Files.writeString(file, "hello");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var resolver = new FileReferenceResolver(tmp);
            var expanded = resolver.expand("@~/doc.md");
            assertTrue(expanded.contains("doc.md"));
            assertTrue(expanded.contains("hello"));
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            try (var walk = Files.walk(path).sorted((a, b) -> -a.compareTo(b))) {
                walk.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
