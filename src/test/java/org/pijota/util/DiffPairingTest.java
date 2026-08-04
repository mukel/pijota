package org.pijota.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which removed line each added line replaced, and the unit-level diff that answers it.
 *
 * <p>
 * Pairing is what makes word emphasis possible: a unified diff prints a run of removals then a run of additions, and
 * nothing in that shape says which line became which. {@link DiffAlgorithm#pairs} decides, and its answer is the
 * difference between emphasis that points at the changed word and emphasis that points at a different line's word.
 */
class DiffPairingTest {

    private static List<DiffAlgorithm.Row> rows(String before, String after) {
        return DiffAlgorithm.diff(before, after);
    }

    /** A compact picture of the rows, so a failure shows the shape rather than a row count. */
    private static String shape(List<DiffAlgorithm.Row> rows) {
        return rows.stream().map(r -> switch (r.kind()) {
            case CONTEXT -> "=";
            case ADD -> "+";
            case DELETE -> "-";
        }).collect(Collectors.joining());
    }

    @Test
    void aOneForOneReplacementPairsTheTwoRows() {
        var rows = rows("a\nOLD\nc\n", "a\nNEW\nc\n");
        var pairs = DiffAlgorithm.pairs(rows);

        assertEquals("=-+==", shape(rows), "fixture: one removal followed by one addition");
        assertEquals(2, pairs.get(1), "the removal at 1 became the addition at 2");
        assertEquals(1, pairs.get(2), "and the mapping reads from either side");
    }

    /** Index-wise, which is the only correspondence a unified diff actually carries. */
    @Test
    void aBlockReplacementPairsRowsInOrder() {
        var rows = rows("x\nA1\nA2\nA3\ny\n", "x\nB1\nB2\nB3\ny\n");
        var pairs = DiffAlgorithm.pairs(rows);

        assertEquals("=---+++==", shape(rows));
        assertEquals(4, pairs.get(1), "first removed pairs with first added");
        assertEquals(5, pairs.get(2));
        assertEquals(6, pairs.get(3), "last removed pairs with last added");
    }

    /**
     * Ragged runs pair as far as they can and stop. Three lines becoming one has no third counterpart, and inventing
     * one would paint emphasis derived from an unrelated line -- worse than showing none.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"A1\\nA2\\nA3\\n|B1\\n|1", "A1\\n|B1\\nB2\\nB3\\n|1",
            "A1\\nA2\\n|B1\\nB2\\nB3\\n|2"})
    void raggedRunsPairOnlyAsFarAsBothSidesReach(String before, String after, int expectedPairs) {
        var pairs = DiffAlgorithm.pairs(rows(before.replace("\\n", "\n"), after.replace("\\n", "\n")));

        assertEquals(expectedPairs * 2, pairs.size(), "each pair is recorded from both sides");
    }

    @Test
    void aPureAdditionPairsNothingBecauseNothingWasReplaced() {
        assertTrue(DiffAlgorithm.pairs(rows("a\nb\n", "a\nNEW\nb\n")).isEmpty());
    }

    @Test
    void aPureDeletionPairsNothingBecauseNothingReplacedIt() {
        assertTrue(DiffAlgorithm.pairs(rows("a\nGONE\nb\n", "a\nb\n")).isEmpty());
    }

    /**
     * A unified diff always prints the removals of a change before its additions. An addition that merely precedes an
     * unrelated later removal is not a replacement, and pairing them would emphasise two unrelated lines against each
     * other.
     */
    @Test
    void anAdditionBeforeAnUnrelatedRemovalIsNotAPair() {
        var rows = rows("keep\nGONE\n", "NEW\nkeep\n");
        var pairs = DiffAlgorithm.pairs(rows);

        assertEquals("+=-=", shape(rows), "fixture: the addition first, then context, then the removal");
        assertTrue(pairs.isEmpty(), () -> "an add that precedes a delete is not a replacement, got " + pairs);
    }

    /** Two separate replacements in one hunk each pair within themselves. */
    @Test
    void separateReplacementsPairIndependently() {
        var rows = rows("A\nx\nB\n", "A2\nx\nB2\n");
        var pairs = DiffAlgorithm.pairs(rows);

        assertEquals("-+=-+=", shape(rows));
        assertEquals(1, pairs.get(0), "first replacement");
        assertEquals(4, pairs.get(3), "second replacement, paired within its own run");
    }

    /** Every pairing is mutual: if a maps to b then b maps to a, which is what lets a row resolve its partner. */
    @Test
    void pairingIsAlwaysMutual() {
        var pairs = DiffAlgorithm.pairs(rows("A1\nA2\nk\nA3\n", "B1\nB2\nk\nB3\n"));

        assertFalse(pairs.isEmpty(), "fixture check: this diff really does pair rows");
        pairs.forEach((from, to) -> assertEquals(from, pairs.get(to), () -> from + " -> " + to + " is not mutual"));
    }

    /**
     * The unit overload is the same pass over arbitrary strings rather than lines. Pinning the equivalence keeps the
     * word diff honest: it relies on this being the line algorithm, not a second implementation that drifts.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"a,b,c|a,x,c", "a,b|a,b", "''|a,b", "a,b|''", "a,a,a|a,a", "a,b,c|c,b,a"})
    void theUnitOverloadAgreesWithTheLineOverload(String before, String after) {
        String[] oldUnits = before.isEmpty() ? new String[0] : before.split(",");
        String[] newUnits = after.isEmpty() ? new String[0] : after.split(",");

        assertEquals(shape(DiffAlgorithm.diff(String.join("\n", oldUnits), String.join("\n", newUnits))),
                                        shape(DiffAlgorithm.diff(oldUnits, newUnits)),
                                        "the unit pass and the line pass must be the same algorithm");
    }

    @Test
    void theUnitOverloadToleratesNulls() {
        assertTrue(DiffAlgorithm.diff((String[]) null, null).isEmpty());
        assertEquals("++", shape(DiffAlgorithm.diff(null, new String[] {"a", "b"})));
        assertEquals("--", shape(DiffAlgorithm.diff(new String[] {"a", "b"}, null)));
    }

    /**
     * Repeated identical lines are where a diff algorithm most easily produces a technically-correct but unreadable
     * answer. The requirement is only that it stays consistent: every line accounted for, once.
     */
    @Test
    void repeatedIdenticalLinesAreAllAccountedFor() {
        var rows = rows("a\na\na\n", "a\na\na\na\n");

        // Four, not three: splitting keeps the empty string after the final newline, and it is common to both.
        assertEquals(4, rows.stream().filter(r -> r.kind() == DiffAlgorithm.Kind.CONTEXT).count());
        assertEquals(1, rows.stream().filter(r -> r.kind() == DiffAlgorithm.Kind.ADD).count());
        assertEquals(0, rows.stream().filter(r -> r.kind() == DiffAlgorithm.Kind.DELETE).count());
    }

    /** Line numbers are what the gutter prints, so they have to count the two sides independently. */
    @Test
    void lineNumbersCountEachSideSeparately() {
        var rows = rows("keep\nGONE\ntail\n", "keep\nNEW1\nNEW2\ntail\n");
        var tail = rows.get(rows.size() - 2); // the last row is the empty string after the final newline

        assertEquals("tail", tail.text(), "fixture: the last row is the trailing context line");
        assertEquals(3, tail.oldLine(), "third line of the old file");
        assertEquals(4, tail.newLine(), "fourth line of the new file");
    }

    /** Distant changes are separate hunks; nearby ones share, so the reader is not shown a gap of two lines. */
    @Test
    void changesFurtherApartThanTwiceTheContextBecomeSeparateHunks() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n";

        assertEquals(2, DiffAlgorithm.hunks(rows(before, before.replace("1\n2", "1\nX").replace("11", "Y")), 3).size(),
                                        "changes at line 2 and line 11 are too far apart to share a hunk");
        assertEquals(1, DiffAlgorithm.hunks(rows(before, before.replace("4", "X").replace("6", "Y")), 3).size(),
                                        "changes two lines apart belong in one hunk");
    }
}
