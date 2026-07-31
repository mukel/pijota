package org.pijota.tui;

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
            assertEquals("hello world", resolver.expand("hello world").text());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void expandAtWithoutPathLeftAlone() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-frr");
        try {
            var resolver = new FileReferenceResolver(tmp);
            assertEquals("see @Test annotation", resolver.expand("see @Test annotation").text());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void expandResolvesFileReference(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("example.txt");
        Files.writeString(file, "contents");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("check @./example.txt").text();

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
            assertEquals("@missing.txt", resolver.expand("@missing.txt").text());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void expandAtSignInMiddleLeftAlone() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-frr");
        try {
            var resolver = new FileReferenceResolver(tmp);
            assertEquals("abc@foo bar", resolver.expand("abc@foo bar").text());
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
        var expanded = resolver.expand("@./a.txt @./b.txt").text();

        assertTrue(expanded.contains("a.txt"));
        assertTrue(expanded.contains("alpha"));
        assertTrue(expanded.contains("b.txt"));
        assertTrue(expanded.contains("beta"));
    }

    @Test
    void expandDirectoryEntryNotExpanded(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve("sub"));

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@sub").text();
        assertEquals("@sub", expanded);
    }

    @Test
    void expandAtSignAtStartWithoutSlashLeftAlone(@TempDir Path tmp) throws Exception {
        var resolver = new FileReferenceResolver(tmp);
        assertEquals("@justword", resolver.expand("@justword").text());
    }

    @Test
    void resolvesRelativeSubdirectoryFile(@TempDir Path tmp) throws Exception {
        var dir = tmp.resolve("sub");
        Files.createDirectory(dir);
        var file = dir.resolve("nested.txt");
        Files.writeString(file, "deep");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("see @./sub/nested.txt").text();

        assertTrue(expanded.contains("nested.txt"));
        assertTrue(expanded.contains("deep"));
    }

    @Test
    void globExpandsMultipleFiles(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "alpha");
        Files.writeString(tmp.resolve("b.txt"), "beta");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@./*.txt").text();

        assertTrue(expanded.contains("alpha"));
        assertTrue(expanded.contains("beta"));
    }

    @Test
    void globNoMatchesReturnsOriginalReference(@TempDir Path tmp) {
        var resolver = new FileReferenceResolver(tmp);
        assertEquals("@./*.nothing", resolver.expand("@./*.nothing").text());
    }

    @Test
    void truncatesLargeFiles(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("big.txt");
        Files.writeString(file, "x".repeat(32_001));

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@./big.txt").text();

        assertTrue(expanded.contains("... (truncated)"));
    }

    @Test
    void referenceAtStartOfLine(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("note.txt");
        Files.writeString(file, "note");

        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("@./note.txt end").text();

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
            var expanded = resolver.expand("@~/doc.md").text();
            assertTrue(expanded.contains("doc.md"));
            assertTrue(expanded.contains("hello"));
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void imageReferenceBecomesAnAttachment(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("shot.png"), new byte[] {1, 2, 3});
        var resolver = new FileReferenceResolver(tmp);
        var ex = resolver.expand("see @./shot.png");

        assertEquals(1, ex.attachments().size());
        assertEquals("image/png", ex.attachments().getFirst().mime());
        assertTrue(ex.text().contains("[shot.png]"), ex.text());
        assertFalse(ex.text().contains("@./shot.png"), ex.text());
    }

    @Test
    void docxReferenceBecomesAnAttachment(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("doc.docx"), new byte[] {1, 2, 3});
        var resolver = new FileReferenceResolver(tmp);
        var ex = resolver.expand("@./doc.docx");

        assertEquals(1, ex.attachments().size());
        assertTrue(ex.attachments().getFirst().mime().contains("wordprocessingml"), ex.attachments().toString());
    }

    @Test
    void oversizedMediaIsNotedNotInlined(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("big.png"), new byte[6 * 1024 * 1024]); // over the 5 MB cap
        var resolver = new FileReferenceResolver(tmp);
        var ex = resolver.expand("@./big.png");

        assertTrue(ex.attachments().isEmpty());
        assertTrue(ex.text().contains("too large or unreadable"), ex.text());
        assertFalse(ex.text().contains("@./big.png"), "must not leave the raw reference");
    }

    @Test
    void unsupportedImageFormatIsNoted(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("photo.heic"), new byte[] {1, 2, 3});
        var resolver = new FileReferenceResolver(tmp);
        var ex = resolver.expand("@./photo.heic");

        assertTrue(ex.attachments().isEmpty());
        assertTrue(ex.text().contains("HEIC"), ex.text());
    }

    @Test
    void doubleQuotedReferenceWithSpacesResolves(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("my file.txt"), "spaced contents");
        var resolver = new FileReferenceResolver(tmp);
        var expanded = resolver.expand("check @\"my file.txt\" please").text();

        assertTrue(expanded.contains("spaced contents"), expanded);
        assertFalse(expanded.contains("@\"my file.txt\""), expanded);
    }

    @Test
    void singleQuotedReferenceWithSpacesResolves(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a b.txt"), "single-quoted contents");
        var resolver = new FileReferenceResolver(tmp);
        assertTrue(resolver.expand("@'a b.txt'").text().contains("single-quoted contents"));
    }

    @Test
    void escapedDoubleQuoteInsideDoubleQuotedReference(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("od\"d.txt"), "weird name"); // file literally named od"d.txt
        var resolver = new FileReferenceResolver(tmp);
        // input is @"od\"d.txt" → unescapes to od"d.txt
        assertTrue(resolver.expand("@\"od\\\"d.txt\"").text().contains("weird name"));
    }

    @Test
    void referenceFormatsBareOrQuotedWithEscaping() {
        assertEquals("@src/Foo.java", FileReferenceResolver.reference("src/Foo.java")); // has / → bare
        assertEquals("@./pom.xml", FileReferenceResolver.reference("pom.xml")); // slash-less → ./ so it resolves
        assertEquals("@\"my docs/report.pdf\"", FileReferenceResolver.reference("my docs/report.pdf"));
        assertEquals("@\"./John's Resume.pdf\"", FileReferenceResolver.reference("John's Resume.pdf")); // ./ + quote
        assertEquals("@\"./a \\\"b\\\" c.txt\"", FileReferenceResolver.reference("a \"b\" c.txt")); // ./ + escape
    }

    @Test
    void specialCharacterFilenamesRoundTripThroughReferenceAndExpand(@TempDir Path tmp) throws Exception {
        for (String name : new String[] {"pom.xml", // slash-less top-level (was left literal before the ./ fix)
                "re@port.txt", // @ inside the name, only a boundary @ triggers parsing
                "John's.txt", // apostrophe, no space → bare
                "a 'b'.txt", // apostrophe + space → double-quoted (no escaping needed for ')
                "a \"b\" c.txt", // double-quote + space → quoted with \" escapes
                "'leading.txt", // leading apostrophe → ./ sidesteps the quote-parse
                "data (1).txt"}) { // parens + space
            Files.writeString(tmp.resolve(name), "RT:" + name);
            var resolver = new FileReferenceResolver(tmp);
            String ref = FileReferenceResolver.reference(name);
            assertTrue(resolver.expand(ref).text().contains("RT:" + name), "did not resolve '" + name + "' via " + ref);
        }
    }

    @Test
    void formattedReferenceRoundTripsThroughExpand(@TempDir Path tmp) throws Exception {
        String name = "tri cky \"quote\".txt";
        Files.writeString(tmp.resolve(name), "round trip");
        var resolver = new FileReferenceResolver(tmp);

        String ref = FileReferenceResolver.reference(name); // @"tri cky \"quote\".txt"
        assertTrue(resolver.expand(ref).text().contains("round trip"), ref);
    }

    @Test
    void mixedBareAndQuotedReferencesBothResolve(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "AAA");
        Files.writeString(tmp.resolve("b c.txt"), "BBB");
        var expanded = new FileReferenceResolver(tmp).expand("see @./a.txt and @\"b c.txt\" please").text();

        assertTrue(expanded.contains("AAA"), expanded);
        assertTrue(expanded.contains("BBB"), expanded);
    }

    @Test
    void atSignNotAtATokenBoundaryStaysLiteral(@TempDir Path tmp) throws Exception {
        var resolver = new FileReferenceResolver(tmp);
        assertEquals("mail user@host.com now", resolver.expand("mail user@host.com now").text()); // not a file ref
    }

    @Test
    void quotedImageWithSpacesBecomesAnAttachment(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("my shot.png"), new byte[] {1, 2, 3});
        var ex = new FileReferenceResolver(tmp).expand("look at @\"my shot.png\"");

        assertEquals(1, ex.attachments().size());
        assertEquals("image/png", ex.attachments().getFirst().mime());
        assertTrue(ex.text().contains("[my shot.png]"), ex.text());
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
