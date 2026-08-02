package org.pijota.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileProcessorTest {

    @Test
    void processEmptyFileArgsAndNullPromptReturnsEmpty() {
        var result = FileProcessor.processFileReferences(List.of(), null);
        assertEquals("", result);
    }

    @Test
    void processEmptyFileArgsAndBlankPromptReturnsPrompt() {
        var result = FileProcessor.processFileReferences(List.of(), "hello");
        assertEquals("hello", result);
    }

    @Test
    void processFileReferenceReadsContent(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.java");
        Files.writeString(file, "public class Test {}");

        var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
        assertTrue(result.contains("public class Test {}"));
        assertTrue(result.contains("test.java (java)"));
    }

    @Test
    void processFileReferenceWithPrompt(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("data.txt");
        Files.writeString(file, "contents");

        var result = FileProcessor.processFileReferences(List.of(file.toString()), "look at this");
        assertTrue(result.startsWith("look at this"));
        assertTrue(result.contains("contents"));
    }

    @Test
    void processMissingFileReportsError(@TempDir Path tmp) {
        var result = FileProcessor.processFileReferences(List.of(tmp.resolve("nope.txt").toString()), null);
        assertTrue(result.contains("[File not found:"));
    }

    @Test
    void processDirectoryListsContents(@TempDir Path tmp) throws Exception {
        var dir = tmp.resolve("subdir");
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");

        var result = FileProcessor.processFileReferences(List.of(dir.toString()), null);
        assertTrue(result.contains("[Directory:"));
        assertTrue(result.contains("a.txt"));
        assertTrue(result.contains("b.txt"));
    }

    @Test
    void processLargeFileReportsTooLarge(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("big.bin");
        var data = new byte[1024 * 1024 * 2];
        Files.write(file, data);

        var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
        assertTrue(result.contains("[File too large:"), "Expected file-too-large message, got: " + result);
    }

    @Test
    void languageDetectionReturnsJava() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("test.java");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(java)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsPython() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("script.py");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(python)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsJavascript() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("app.js");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(javascript)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsTypescript() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("app.ts");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(typescript)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsRust() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("main.rs");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(rust)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsGo() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("main.go");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(go)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsBash() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("script.sh");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(bash)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsMarkdown() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("readme.md");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(markdown)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsJson() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("data.json");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(json)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsYaml() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("config.yaml");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(yaml)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void languageDetectionReturnsTextForUnknown() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("data.xyz");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(text)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void multipleFileReferences(@TempDir Path tmp) throws Exception {
        var a = tmp.resolve("a.java");
        var b = tmp.resolve("b.py");
        Files.writeString(a, "java code");
        Files.writeString(b, "python code");

        var result = FileProcessor.processFileReferences(List.of(a.toString(), b.toString()), null);
        assertTrue(result.contains("java code"));
        assertTrue(result.contains("python code"));
        assertTrue(result.contains("(java)"));
        assertTrue(result.contains("(python)"));
    }

    @Test
    void promptsWithAtSignTriggersProcessing(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("notes.txt");
        Files.writeString(file, "inline notes");

        var result = FileProcessor.processFileReferences(List.of(file.toString()), "check @notes.txt");
        assertTrue(result.contains("check @notes.txt"));
        assertTrue(result.contains("inline notes"));
    }

    @Test
    void languageDetectionCaseInsensitive() throws Exception {
        Path tmp = Files.createTempDirectory("pijota-fp");
        try {
            var file = tmp.resolve("Test.JAVA");
            Files.writeString(file, "x");
            var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
            assertTrue(result.contains("(java)"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void stripTrailingRemovesFinalNewlines(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("data.txt");
        Files.writeString(file, "content");

        var result = FileProcessor.processFileReferences(List.of(file.toString()), null);
        assertFalse(result.endsWith("\n"));
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
