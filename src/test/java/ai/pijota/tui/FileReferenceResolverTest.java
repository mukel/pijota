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
