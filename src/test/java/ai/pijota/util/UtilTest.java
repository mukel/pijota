package ai.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {
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
        assertEquals(10, Util.getInt(java.util.Map.of("x", (Object) 10), "x", 0));
        assertEquals(0, Util.getInt(java.util.Map.of(), "x", 0));
    }
}
