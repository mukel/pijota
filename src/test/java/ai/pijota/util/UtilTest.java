package ai.pijota.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {
    @Test
    void pluralAppendsSExceptForOne() {
        assertEquals("1 item", Util.plural(1, "item"));
        assertEquals("0 items", Util.plural(0, "item"));
        assertEquals("3 tools", Util.plural(3, "tool"));
    }

    @Test
    void shellQuoteIfNeededQuotesOnlyWhenNecessary() {
        assertEquals("src/Foo.java", Util.shellQuoteIfNeeded("src/Foo.java")); // safe → bare
        assertEquals("~/notes.md", Util.shellQuoteIfNeeded("~/notes.md")); // ~ kept bare so it still expands
        assertEquals("'my file.txt'", Util.shellQuoteIfNeeded("my file.txt")); // space → quoted
        assertEquals("'a \"b\" c.txt'", Util.shellQuoteIfNeeded("a \"b\" c.txt")); // double-quotes literal in '...'
        assertEquals("'John'\\''s.txt'", Util.shellQuoteIfNeeded("John's.txt")); // ' → '\'' dance
        assertEquals("'a$b *.txt'", Util.shellQuoteIfNeeded("a$b *.txt")); // $ and glob neutralized
        assertEquals("''", Util.shellQuoteIfNeeded("")); // empty → ''
    }

    @Test
    void fmtCountFormatsHumanReadableNumbers() {
        assertEquals("0", Util.fmtCount(0));
        assertEquals("42", Util.fmtCount(42));
        assertEquals("1K", Util.fmtCount(1200));
        assertEquals("10K", Util.fmtCount(10240));
        assertEquals("1.5M", Util.fmtCount(1_540_000));
    }

    @Test
    void formatDurationFormatsMillisecondsToShortForm() {
        assertEquals("0s", Util.formatDuration(0));
        assertEquals("5s", Util.formatDuration(5_000));
        assertEquals("2m30s", Util.formatDuration(150_000));
        assertEquals("10m", Util.formatDuration(600_000));
        assertEquals("1h", Util.formatDuration(3_600_000));
        assertEquals("1h1m5s", Util.formatDuration(3_665_000));
        assertEquals("2h30s", Util.formatDuration(7_230_000));
    }

    @Test
    void deltaPrefixEmitsOnlyAddedSuffix() {
        assertEquals("world", Util.deltaPrefix("hello world", "hello "));
        assertEquals("hello", Util.deltaPrefix("hello", null));
        assertEquals("", Util.deltaPrefix("hello", "hello"));
        assertEquals("full", Util.deltaPrefix("full", "different"));
    }

    @Test
    void truncatePreservesShortStringsAndCutsLongOnes() {
        assertEquals("ab", Util.truncate("ab", 5));
        assertEquals("hello...", Util.truncate("hello world", 5));
    }

    @Test
    void escapeJsonHandlesAllSpecialCharacters() {
        assertEquals("line1\\nline2", Util.escapeJson("line1\nline2"));
        assertEquals("quote \\\" here", Util.escapeJson("quote \" here"));
        assertEquals("back\\\\slash", Util.escapeJson("back\\slash"));
    }

    @Test
    void countOccurrencesCountsNonOverlappingMatches() {
        assertEquals(2, Util.countOccurrences("a a a", "a "));
        assertEquals(0, Util.countOccurrences("abc", "xyz"));
        assertEquals(0, Util.countOccurrences(null, "a"));
        assertEquals(0, Util.countOccurrences("abc", ""));
    }

    @Test
    void coalesceReturnsFirstNonBlank() {
        assertEquals("b", Util.coalesce(null, "", "b", "c"));
        assertNull(Util.coalesce(null, "", "  "));
    }

    @Test
    void stripSystemRemindersRemovesXmlTagsAndContent() {
        assertEquals("before  after", Util.stripSystemReminders("before <system-reminder>x</system-reminder> after"));
    }

    @Test
    void getIntReturnsNumberOrDefault() {
        assertEquals(10, Util.getInt(Map.of("x", (Object) 10), "x", 0));
        assertEquals(0, Util.getInt(Map.of(), "x", 0));
    }

    @Test
    void truncateHandlesNullAndEdgeLengths() {
        assertEquals("", Util.truncate(null, 5));
        assertEquals("hello", Util.truncate("hello", 5));
    }

    @Test
    void truncateWordsCutsAtWordBoundaryWhenReasonable() {
        assertEquals("hello world", Util.truncateWords("hello world", 20));
        assertEquals("hello...", Util.truncateWords("hello world", 8));
        assertEquals("hel...", Util.truncateWords("hello world", 6));
    }

    @Test
    void escapeJsonEscapesControlCharacters() {
        assertEquals("\\b\\f\\r\\t", Util.escapeJson("\b\f\r\t"));
        assertEquals("\\u001f", Util.escapeJson("\u001f"));
        assertEquals("plain", Util.escapeJson("plain"));
    }

    @Test
    void getIntFallsBackForWrongType() {
        assertEquals(5, Util.getInt(Map.of("x", "not a number"), "x", 5));
        var withNull = new HashMap<String, Object>();
        withNull.put("x", null);
        assertEquals(5, Util.getInt(withNull, "x", 5));
        assertEquals(5, Util.getInt(Map.of(), "x", 5));
    }

    @Test
    void getLongFallsBackForWrongType() {
        assertEquals(5L, Util.getLong(Map.of("x", "not a number"), "x", 5L));
        assertEquals(5L, Util.getLong(Map.of("x", true), "x", 5L));
    }

    @Test
    void getStringIgnoresBlankValues() {
        assertEquals("value", Util.getString(Map.of("k", "value"), "k"));
        assertNull(Util.getString(Map.of("k", "   "), "k"));
        assertEquals("default", Util.getString(Map.of("k", ""), "k", "default"));
    }

    @Test
    void getFirstStringScansKeysInOrder() {
        assertEquals("a", Util.getFirstString(Map.of("x", "a", "y", "b"), "x", "y"));
        assertEquals("b", Util.getFirstString(Map.of("y", "b"), "x", "y"));
        assertNull(Util.getFirstString(Map.of(), "x", "y"));
    }

    @Test
    void parseJsonArgsReturnsEmptyMapOnInvalidInput() {
        assertTrue(Util.parseJsonArgs("not json").isEmpty());
        assertTrue(Util.parseJsonArgs("").isEmpty());
        assertEquals("v", Util.parseJsonArgs("{\"k\":\"v\"}").get("k"));
    }

    @Test
    void parseBashArgsExtractsCommandOrNull() {
        assertEquals("ls", Util.parseBashArgs("{\"command\":\"ls\"}"));
        assertNull(Util.parseBashArgs("{\"other\":\"x\"}"));
        assertNull(Util.parseBashArgs("bad json"));
    }

    @Test
    void coalesceReturnsNullWhenAllBlank() {
        assertNull(Util.coalesce("", "   ", null));
    }

    @Test
    void stripSystemRemindersHandlesBlankAndMultiple() {
        assertEquals("", Util.stripSystemReminders(null));
        assertEquals("", Util.stripSystemReminders("   "));
        assertEquals("x", Util.stripSystemReminders(
                                        "<system-reminder>a</system-reminder>x<system-reminder>b</system-reminder>"));
    }

    @Test
    void resolveSearchPathHandlesRelativeAndAbsolute() {
        Path base = Path.of("/tmp");
        assertEquals(Path.of("/tmp/foo").toAbsolutePath().normalize(), Util.resolveSearchPath(base, "foo"));
        assertEquals(Path.of("/abs/path").normalize(), Util.resolveSearchPath(base, "/abs/path"));
    }

    @Test
    void relativePathFallsBackToOriginal() {
        Path file = Path.of("/tmp/foo.txt");
        assertEquals("foo.txt", Util.relativePath(file, "/tmp"));
        assertEquals(file.toString(), Util.relativePath(file, "/nowhere"));
    }

    @Test
    void countNewlinesCountsLineBreaks() {
        assertEquals(0, Util.countNewlines(null));
        assertEquals(0, Util.countNewlines(""));
        assertEquals(2, Util.countNewlines("a\nb\nc"));
    }

    @Test
    void resolveDirReturnsAbsolutePath() {
        assertNotNull(Util.resolveDir(null));
        assertTrue(Util.resolveDir(null).isAbsolute());
        assertEquals(Path.of("/tmp").toAbsolutePath().normalize(), Util.resolveDir("/tmp"));
    }

    @Test
    void truncateToFileSpillsLargeOutputToTempFile() throws IOException {
        String text = "x".repeat(10_000);
        var result = Util.truncateToFile(text, 1000, "util-test");
        assertNotEquals(text, result.text());
        assertTrue((Boolean) result.details().get("truncated"));
        assertNotNull(result.details().get("fullOutputPath"));
        Path fullPath = Path.of((String) result.details().get("fullOutputPath"));
        assertEquals(text, Files.readString(fullPath));
        Files.deleteIfExists(fullPath);
    }

    @Test
    void truncateToFileReturnsSmallOutputUnchanged() throws IOException {
        var result = Util.truncateToFile("short", 100, "util-test");
        assertEquals("short", result.text());
        assertTrue(result.details().isEmpty());
    }

    @Test
    void truncateHeadTailBoundsOutput() {
        assertEquals("", Util.truncateHeadTail(null, 10));
        assertEquals("hi", Util.truncateHeadTail("hi", 10));
        String longText = "a".repeat(100);
        String out = Util.truncateHeadTail(longText, 10);
        assertTrue(out.startsWith("aaaaaaa"));
        assertTrue(out.contains("truncated"));
        assertTrue(out.endsWith("aaa"));
    }

    @Test
    void truncateHeadTailWithFullOutputPath() {
        String out = Util.truncateHeadTail("a".repeat(100), 10, "/tmp/out.txt");
        assertTrue(out.contains("/tmp/out.txt"));
    }

    @Test
    void fmtCountBoundaries() {
        assertEquals("999", Util.fmtCount(999));
        assertEquals("1K", Util.fmtCount(1000));
        assertEquals("999K", Util.fmtCount(999_999));
        assertEquals("1.0M", Util.fmtCount(1_000_000));
    }

    @Test
    void formatRelativeUsesShortUnits() {
        long now = System.currentTimeMillis();
        assertTrue(Util.formatRelative(now + 5_000).contains("s"));
        assertTrue(Util.formatRelative(now - 120_000).contains("m"));
        assertTrue(Util.formatRelative(now + 7_200_000).contains("h"));
        assertTrue(Util.formatRelative(now + 172_800_000L).contains("d"));
    }

    @Test
    void formatDurationOneMinute() {
        assertEquals("1m", Util.formatDuration(60_000));
    }

    @Test
    void shortUuidFormatsCorrectly() {
        assertEquals(8, Util.shortUuid().length());
        assertEquals(16, Util.shortUuid(16).length());
        assertTrue(Util.shortUuid(50).length() <= 32);
    }

    @Test
    void shellQuoteEscapesSingleQuotes() {
        assertEquals("'hello'", Util.shellQuote("hello"));
        assertEquals("'it'\\''s'", Util.shellQuote("it's"));
    }

    @Test
    void firstLineExtractsFirstNonBlankLine() {
        assertEquals("", Util.firstLine(null));
        assertEquals("", Util.firstLine("   "));
        assertEquals("first", Util.firstLine("first\nsecond\n"));
    }

    @Test
    void resolvePathPrefersPathThenFilePath() {
        assertEquals("/a", Util.resolvePath(Map.of("path", "/a", "filePath", "/b")));
        assertEquals("/b", Util.resolvePath(Map.of("filePath", "/b")));
        assertEquals("/c", Util.resolvePath(Map.of("file_path", "/c")));
        assertNull(Util.resolvePath(Map.of()));
    }

    @Test
    void resolvePathWithCwdNormalizesPaths() {
        assertEquals("/tmp/a", Util.resolvePath(Map.of("path", "/tmp/a"), "/cwd"));
        assertEquals("/cwd/rel", Util.resolvePath(Map.of("path", "rel"), "/cwd"));
    }

    @Test
    void shortenCwdReplacesHomeWithTilde() {
        String home = System.getProperty("user.home");
        if (home == null) {
            String cwd = Util.shortenCwd("/tmp");
            assertEquals("/tmp", cwd);
        } else {
            assertEquals("~", Util.shortenCwd(home));
            assertEquals("~/foo", Util.shortenCwd(home + "/foo"));
            assertEquals("/tmp", Util.shortenCwd("/tmp"));
        }
    }
}
