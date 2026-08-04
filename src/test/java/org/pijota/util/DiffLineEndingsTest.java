package org.pijota.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the diff treats the characters that end lines, which is the one part of a file a reader never sees and an editor
 * changes without being asked.
 */
class DiffLineEndingsTest {

    private static List<DiffAlgorithm.Row> diff(String before, String after) {
        return DiffAlgorithm.diff(before, after);
    }

    private static long changed(List<DiffAlgorithm.Row> rows) {
        return rows.stream().filter(r -> r.kind() != DiffAlgorithm.Kind.CONTEXT).count();
    }

    /**
     * Lines are split on any terminator, so the terminator itself is never compared: converting a file between CRLF and
     * LF produces no diff at all.
     *
     * <p>
     * This is a deliberate choice and the one most tools make, because the alternative is that a colleague on another
     * platform turns every line of every file red. It is worth pinning because the consequence is real: a change that
     * is only a line-ending conversion renders as nothing, and a reader has to know that "no diff" can mean "only the
     * terminators moved".
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"a\\nb\\n|a\\r\\nb\\r\\n", "a\\r\\nb\\r\\n|a\\nb\\n", "a\\rb|a\\nb"})
    void aChangeOfLineTerminatorAloneIsNotADifference(String before, String after) {
        assertEquals(0, changed(diff(unescape(before), unescape(after))),
                                        "line terminators are not compared, by design");
    }

    /**
     * The final newline is different: splitting keeps the empty string after it, so gaining one is gaining a line. That
     * is visible, which is right -- whether a file ends with a newline is a real property of the file.
     */
    @Test
    void gainingAFinalNewlineShowsAsAnAddedLine() {
        var rows = diff("a\nb", "a\nb\n");

        assertEquals(1, changed(rows));
        assertEquals(DiffAlgorithm.Kind.ADD, rows.get(rows.size() - 1).kind());
    }

    @Test
    void losingAFinalNewlineShowsAsARemovedLine() {
        var rows = diff("a\nb\n", "a\nb");

        assertEquals(1, changed(rows));
        assertEquals(DiffAlgorithm.Kind.DELETE, rows.get(rows.size() - 1).kind());
    }

    @Test
    void aFileWithNoTrailingNewlineDiffsNormally() {
        var rows = diff("alpha\nbeta", "alpha\ngamma");

        assertEquals(2, changed(rows), "one line replaced, terminators irrelevant");
    }

    /** Mixed terminators inside one file still compare line for line. */
    @Test
    void mixedTerminatorsCompareLineForLine() {
        assertEquals(0, changed(diff("a\r\nb\nc\r\n", "a\nb\r\nc\n")));
        assertEquals(2, changed(diff("a\r\nb\nc\r\n", "a\nCHANGED\r\nc\n")));
    }

    @Test
    void anEmptyFileAgainstAnEmptyFileIsNoDifference() {
        assertEquals(0, changed(diff("", "")));
        assertEquals(0, changed(diff(null, null)));
    }

    @Test
    void aFileGainingItsFirstLineIsAnAddition() {
        assertEquals(1, changed(diff("", "hello\n")) > 0 ? 1 : 0, "an empty file gaining content shows an addition");
    }

    private static String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n");
    }
}
