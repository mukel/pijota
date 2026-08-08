package org.pijota.util;

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

    /**
     * The status bar redraws this several times a second. Trimming costs a column at 4.30K and hands it back at 4.31K,
     * so the line twitches while nothing has happened. Digits before the point are left alone: they change rarely, and
     * only ever grow.
     */
    @Test
    void fmtCountFixedKeepsBothFractionalDigitsSoTheLineDoesNotTwitch() {
        assertEquals("4.27K", Util.fmtCountFixed(4270));
        assertEquals("4.30K", Util.fmtCountFixed(4300), "not 4.3K, which is a column narrower");
        assertEquals("1.00K", Util.fmtCountFixed(1000));
        assertEquals("1.05M", Util.fmtCountFixed(1_050_000));
        assertEquals("2.00M", Util.fmtCountFixed(2_000_000));

        // The point of the whole thing: one width for every figure with the same digits before the point.
        for (long v : new long[] {1000, 4270, 4300, 9990})
            assertEquals(5, Util.fmtCountFixed(v).length(), "should be 5 columns: " + Util.fmtCountFixed(v));
        for (long v : new long[] {12_200, 12_210, 99_990})
            assertEquals(6, Util.fmtCountFixed(v).length(), "should be 6 columns: " + Util.fmtCountFixed(v));
        // Rounding may carry into the integer part, which is a real column gained, not a twitch: it does not come back.
        assertEquals("10.00K", Util.fmtCountFixed(9999));

        // Under a thousand there is no point to keep, and trimming never applied there anyway.
        assertEquals("600", Util.fmtCountFixed(600));
        assertEquals("0", Util.fmtCountFixed(0));
    }

    @Test
    void fmtCountPreciseKeepsTwoTrimmedFractionalDigits() {
        assertEquals("0", Util.fmtCountPrecise(0));
        assertEquals("600", Util.fmtCountPrecise(600));
        assertEquals("1.22K", Util.fmtCountPrecise(1220)); // where fmtCount rounds to a lossy "1K"
        assertEquals("1K", Util.fmtCountPrecise(1000)); // trailing zeros trimmed
        assertEquals("12.3K", Util.fmtCountPrecise(12_300));
        assertEquals("1.05M", Util.fmtCountPrecise(1_050_000));
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

    /**
     * Providers word a reachability failure at length ("...Is it running and is the base URL correct?"). Repeating it
     * per attempt turned five retries into ten wrapped lines of the same sentence, so only the first carries it.
     */
    @Test
    void retryLineSaysWhyOnceAndThenJustTheWait() {
        String why = "couldn't reach the model server (connection refused)";

        assertEquals("retrying in 2s (1/6) · " + why, Util.retryLine(1, 6, 2_000, why));
        assertEquals("retrying in 4s (2/6)", Util.retryLine(2, 6, 4_000, why));
        assertEquals("retrying in 1m4s (6/6)", Util.retryLine(6, 6, 64_000, why));
    }

    /** A provider that fails with no message must not leave a dangling separator. */
    @Test
    void retryLineOmitsTheSeparatorWhenThereIsNoReason() {
        assertEquals("retrying in 2s (1/6)", Util.retryLine(1, 6, 2_000, null));
        assertEquals("retrying in 2s (1/6)", Util.retryLine(1, 6, 2_000, "   "));
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
        assertNotNull(result.details().get("outputPath"));
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
    void replaceFileReplacesTargetWithTempContent() throws IOException {
        Path dir = Files.createTempDirectory("pijota-replace");
        Path target = dir.resolve("target.txt");
        Path tmp = dir.resolve("target.txt.tmp");
        try {
            Files.writeString(target, "old");
            Files.writeString(tmp, "new");

            Util.replaceFile(tmp, target);

            assertEquals("new", Files.readString(target));
            assertFalse(Files.exists(tmp));
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(target);
            Files.deleteIfExists(dir);
        }
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
    void ellipsizeClipsToBudgetWithASingleGlyph() {
        assertEquals("", Util.ellipsize(null, 10));
        assertEquals("", Util.ellipsize("   ", 10));
        assertEquals("short", Util.ellipsize("short", 10));
        String s = Util.ellipsize("abcdefghij", 5);
        assertEquals(5, s.length(), "never wider than the budget"); // 4 chars + "…"
        assertTrue(s.endsWith("…"), s);
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

    /**
     * The screens printed before there is a terminal to lay text out: the first-run guidance and the trust prompt. Both
     * had their line breaks typed in, to widths that were not the reader's, so a narrower terminal folded them a second
     * time and split words in half.
     */
    @Test
    void wrapFoldsAtSpacesAndNeverMidWord() {
        String text = "the quick brown fox jumps over the lazy dog";

        for (int width = 12; width <= 44; width++) {
            String folded = Util.wrap(text, width, "");
            for (String line : folded.split("\n"))
                assertTrue(line.length() <= width, "width " + width + " produced a " + line.length() + " column line: ["
                                                + line + "]");
            assertEquals(text, folded.replace("\n", " "), "folding must not change the words, at width " + width);
        }
    }

    /** A leading indent places the line against its neighbours; folding may not eat it. */
    @Test
    void wrapKeepsTheIndentAndTheColumnTheTextStartsIn() {
        String option = "  y   trust it; remembered for next time, change later with /trust";

        String folded = Util.wrap(option, 58, "      ");

        assertTrue(folded.startsWith("  y   trust"),
                                        () -> "the option lost the column it is written in: [" + folded + "]");
        var lines = folded.split("\n");
        assertTrue(lines.length > 1, "fixture: this should have folded");
        for (int i = 1; i < lines.length; i++)
            assertTrue(lines[i].startsWith("      "), "a continuation must sit under the text, not under the marker: ["
                                            + lines[i] + "]");
    }

    /** One word longer than the whole width still has to come out whole, on its own line. */
    @Test
    void wrapDoesNotBreakAWordThatIsWiderThanTheLine() {
        String folded = Util.wrap("a ANTHROPIC_API_KEY b", 10, "");

        assertTrue(folded.contains("ANTHROPIC_API_KEY"), () -> "the word was cut: [" + folded.replace("\n", "|") + "]");
    }

    /** COLUMNS when the shell exports it, and otherwise the 80 the help text is authored for. */
    @Test
    void terminalWidthFallsBackToEighty() {
        assertEquals(80, Util.terminalWidth(), "a shell does not export COLUMNS, so a test run sees the fallback");
    }
}
