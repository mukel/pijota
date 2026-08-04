package org.pijota.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure half of word-level diff emphasis: which ranges of two versions of a line differ, and, more importantly, when
 * to say nothing at all.
 */
class WordDiffTest {

    /** The substrings a set of spans selects, which is what the reader will see highlighted. */
    private static List<String> highlighted(String line, List<WordDiff.Span> spans) {
        return spans.stream().map(s -> line.substring(s.start(), s.end())).toList();
    }

    private static List<String> oldHighlights(String oldLine, String newLine) {
        return highlighted(oldLine, WordDiff.spans(oldLine, newLine).oldSpans());
    }

    private static List<String> newHighlights(String oldLine, String newLine) {
        return highlighted(newLine, WordDiff.spans(oldLine, newLine).newSpans());
    }

    // ---------- tokenisation ----------

    @Test
    void identifiersAreWholeTokensAndPunctuationStandsAlone() {
        assertEquals(List.of("foo_1", ".", "bar", "(", ")"), WordDiff.tokenize("foo_1.bar()"));
    }

    @Test
    void whitespaceIsItsOwnTokenSoIndentationCanChange() {
        assertEquals(List.of("    ", "return", " ", "x", ";"), WordDiff.tokenize("    return x;"));
    }

    @Test
    void tokensCoverTheLineExactlyWithNothingAddedOrLost() {
        for (String line : List.of("a.b(c, 1)", "   ", "", "x", "  a  b  ", "日本語 = \"値\";"))
            assertEquals(line, String.join("", WordDiff.tokenize(line)), "tokens must reassemble into the line");
    }

    @Test
    void charactersOutsideTheBasicPlaneStayWhole() {
        assertEquals(List.of("x", " ", "=", " ", "\"", "😀", "\"", ";"), WordDiff.tokenize("x = \"😀\";"));
    }

    // ---------- what gets emphasised ----------

    @Test
    void aRenamedIdentifierEmphasisesOnlyThatIdentifier() {
        assertEquals(List.of("oldName"), oldHighlights("int oldName = compute(a, b);", "int newName = compute(a, b);"));
        assertEquals(List.of("newName"), newHighlights("int oldName = compute(a, b);", "int newName = compute(a, b);"));
    }

    @Test
    void aChangedArgumentEmphasisesOnlyThatArgument() {
        assertEquals(List.of("32"), newHighlights("resize(buffer, 16);", "resize(buffer, 32);"));
    }

    @Test
    void aPureInsertionEmphasisesOnlyTheInsertedText() {
        assertEquals(List.of(), oldHighlights("call(a);", "call(a, b);"), "nothing was removed from the old line");
        assertEquals(List.of(", b"), newHighlights("call(a);", "call(a, b);"));
    }

    @Test
    void aPureDeletionEmphasisesOnlyOnTheOldSide() {
        assertEquals(List.of(", b"), oldHighlights("call(a, b);", "call(a);"));
        assertEquals(List.of(), newHighlights("call(a, b);", "call(a);"));
    }

    /** A change in indentation is a real edit; a diff that hid it would misrepresent the file. */
    @Test
    void reindentationIsEmphasisedRatherThanHidden() {
        var result = WordDiff.spans("    return x;", "        return x;");
        assertFalse(result.isEmpty(), "whitespace is a token, so this is a change like any other");
        assertEquals(List.of("        "), newHighlights("    return x;", "        return x;"));
    }

    /**
     * Punctuation is tokenised one character at a time, so turning {@code <} into {@code <=} emphasises the inserted
     * {@code =} and not the whole operator. That is the minimal truthful answer, and it needs no table of which
     * character pairs happen to form an operator in which language.
     */
    @Test
    void aOneCharacterChangeInsideALongLineIsFoundExactly() {
        String before = "if (index < limit && buffer.remaining() > 0) { flush(buffer, index, limit); }";
        String after = "if (index <= limit && buffer.remaining() > 0) { flush(buffer, index, limit); }";
        assertEquals(List.of("="), newHighlights(before, after));
        assertEquals(List.of(), oldHighlights(before, after), "nothing was removed");
    }

    // ---------- when it declines ----------

    /** Every token different is a rewrite. Emphasising all of it claims to point somewhere and points everywhere. */
    @Test
    void aWholeLineRewriteIsNotEmphasisedAtAll() {
        assertTrue(WordDiff.spans("int total = sum(values);", "log.warn(\"nothing here matches\");").isEmpty());
    }

    /**
     * Same shape, every name different. This is the case word diff exists for: the punctuation holds still and the
     * three identifiers light up, so the reader sees what changed without comparing character by character. An earlier
     * version of this test asserted the opposite, on the theory that shared punctuation is incidental. Reading the
     * output settled it.
     */
    @Test
    void aLineWhoseShapeSurvivesEmphasisesTheNamesThatDidNot() {
        assertEquals(List.of("alpha", "beta", "gamma"), oldHighlights("alpha(beta, gamma);", "delta(epsilon, zeta);"));
        assertEquals(List.of("delta", "epsilon", "zeta"),
                                        newHighlights("alpha(beta, gamma);", "delta(epsilon, zeta);"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "x", "return;"})
    void identicalLinesProduceNothing(String line) {
        assertTrue(WordDiff.spans(line, line).isEmpty());
    }

    @Test
    void nullsAreNotAnError() {
        assertTrue(WordDiff.spans(null, "x").isEmpty());
        assertTrue(WordDiff.spans("x", null).isEmpty());
    }

    /** Myers is O(nd) on the interactive draw path; a generated line is not worth stalling a redraw for. */
    @Test
    void anAbsurdlyLongLineIsSkippedRatherThanStallingTheRedraw() {
        String huge = "a,".repeat(500);
        assertTrue(WordDiff.spans(huge, huge + "b").isEmpty(), "past the token cap, word detail is declined");
    }

    // ---------- properties that hold for every input ----------

    @ParameterizedTest
    @CsvSource({"'int a = 1;','int b = 1;'", "'call(x)','call(y)'", "'  a','    a'", "'foo(1, 2)','foo(1, 2, 3)'",
            "'x = compute(a);','x = compute(a, b);'", "'日本語 = 1;','日本語 = 2;'"})
    void spansAreOrderedDisjointAndInsideTheirLine(String before, String after) {
        var result = WordDiff.spans(before, after);
        assertSpansWellFormed(result.oldSpans(), before);
        assertSpansWellFormed(result.newSpans(), after);
    }

    private static void assertSpansWellFormed(List<WordDiff.Span> spans, String line) {
        int previousEnd = 0;
        for (var span : spans) {
            assertTrue(span.start() >= previousEnd, "spans must be ordered and disjoint: " + spans);
            assertTrue(span.end() <= line.length(), "a span ran past the end of [" + line + "]: " + spans);
            assertTrue(span.end() > span.start(), "an empty span highlights nothing: " + spans);
            previousEnd = span.end();
        }
    }

    /**
     * The whole point: applying the new line's spans to the old line's unemphasised remainder reconstructs nothing that
     * moved. Stated as the property that matters -- every emphasised range really differs between the two sides --
     * rather than by reassembling the leftovers, which an earlier version did and which broke on its own seams.
     */
    @ParameterizedTest
    @CsvSource({"'int a = 1;','int b = 1;'", "'resize(buf, 16)','resize(buf, 32)'",
            "'x = compute(a);','x = compute(a, b);'", "'alpha(beta);','delta(beta);'"})
    void everyEmphasisedRangeIsGenuinelyDifferent(String before, String after) {
        var result = WordDiff.spans(before, after);
        assertFalse(result.isEmpty(), "fixture check: these pairs are similar enough to emphasise");
        for (var span : result.newSpans()) {
            String emphasised = after.substring(span.start(), span.end());
            assertFalse(emphasised.isEmpty(), "an empty highlight points at nothing");
            assertNotEquals(emphasised, before.substring(Math.min(span.start(), before.length()),
                                            Math.min(span.end(), before.length())),
                                            "[" + emphasised + "] is identical at the same offset in the old line");
        }
    }

    /** Changed tokens with nothing unchanged between them are one highlight: three abutting boxes read as one box. */
    @Test
    void abuttingChangedTokensBecomeOneRegion() {
        String after = "total = x*y;";
        var spans = WordDiff.spans("total = a+b;", after).newSpans();
        assertEquals(1, spans.size(), "a, + and b all changed, so there is no gap to draw: " + spans);
        assertEquals("x*y", after.substring(spans.get(0).start(), spans.get(0).end()));
    }

    /** But an unchanged token between two changed ones is a real gap, and painting over it would be a lie. */
    @Test
    void anUnchangedTokenBetweenChangesIsLeftAlone() {
        assertEquals(List.of("x", "y"), newHighlights("call(a b, keep);", "call(x y, keep);"),
                                        "the space between them did not change");
    }

    @Test
    void coversAnswersForEveryOffsetOfALine() {
        var spans = List.of(new WordDiff.Span(2, 5));
        assertFalse(WordDiff.covers(spans, 1));
        assertTrue(WordDiff.covers(spans, 2));
        assertTrue(WordDiff.covers(spans, 4));
        assertFalse(WordDiff.covers(spans, 5), "the range is half-open");
        assertFalse(WordDiff.covers(List.of(), 0));
    }

    // ---------- whitespace: the changes a reader cannot otherwise see ----------

    @Test
    void aTabIsItsOwnTokenLikeAnyOtherWhitespace() {
        assertEquals(List.of("\t", "if", " ", "(", "a", ")"), WordDiff.tokenize("\tif (a)"));
    }

    /** A run of mixed whitespace is one token: it is one stretch of blank to a reader, however it is spelled. */
    @Test
    void mixedWhitespaceRunsIntoASingleToken() {
        assertEquals(List.of("  \t ", "x"), WordDiff.tokenize("  \t x"));
    }

    /**
     * Retabbing is the change that is hardest to see and easiest to make by accident. Emphasis is a background, so it
     * shows on blank characters, which is the one thing a foreground highlight could never do.
     */
    @Test
    void spacesBecomingATabIsEmphasised() {
        assertEquals(List.of("    "), oldHighlights("    a();", "\ta();"));
        assertEquals(List.of("\t"), newHighlights("    a();", "\ta();"));
    }

    @Test
    void aTabBecomingSpacesIsEmphasised() {
        assertEquals(List.of("\t"), oldHighlights("\ta();", "    a();"));
        assertEquals(List.of("    "), newHighlights("\ta();", "    a();"));
    }

    @Test
    void trailingWhitespaceAddedIsEmphasised() {
        assertEquals(List.of(), oldHighlights("int x = 1;", "int x = 1;   "));
        assertEquals(List.of("   "), newHighlights("int x = 1;", "int x = 1;   "));
    }

    @Test
    void trailingWhitespaceRemovedIsEmphasised() {
        assertEquals(List.of("   "), oldHighlights("int x = 1;   ", "int x = 1;"));
        assertEquals(List.of(), newHighlights("int x = 1;   ", "int x = 1;"));
    }

    @Test
    void aChangeInTheAmountOfWhitespaceIsEmphasised() {
        assertEquals(List.of("  "), oldHighlights("a  b", "a b"));
        assertEquals(List.of(" "), newHighlights("a  b", "a b"));
    }

    @Test
    void deeperIndentationIsEmphasisedAndShallowerIsToo() {
        assertEquals(List.of("        "), newHighlights("    x();", "        x();"));
        assertEquals(List.of("  "), newHighlights("    x();", "  x();"));
    }

    /** A line that is only whitespace still diffs; there is nothing else on it to compare. */
    @Test
    void aWhitespaceOnlyLineIsComparedAsWhitespace() {
        var result = WordDiff.spans("    ", "\t");
        assertFalse(result.isEmpty(), "one blank line became another kind of blank line");
        assertEquals(List.of("    "), highlighted("    ", result.oldSpans()));
        assertEquals(List.of("\t"), highlighted("\t", result.newSpans()));
    }

    @Test
    void anEmptyLineGainingWhitespaceIsEmphasised() {
        assertEquals(List.of("   "), newHighlights("", "   "));
    }

    /**
     * A non-breaking space is not whitespace to {@link Character#isWhitespace}, so it tokenises on its own rather than
     * joining the run beside it. That is the behaviour worth pinning: it means an NBSP smuggled into indentation shows
     * up as its own changed token instead of hiding inside a blank run.
     */
    @Test
    void aNonBreakingSpaceIsNotTreatedAsOrdinaryWhitespace() {
        assertEquals(List.of(" ", "\u00A0", " ", "x"), WordDiff.tokenize(" \u00A0 x"));
        assertFalse(WordDiff.spans("  x", " \u00A0x").isEmpty(), "a smuggled NBSP is a real difference");
    }

    @Test
    void bothEndsOfALineCanChangeAtOnce() {
        assertEquals(List.of("  ", "   "), newHighlights("    x();", "  x();   "));
    }
}
