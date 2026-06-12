package ai.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiffAlgorithmTest {
    @Test
    void detectsAdditionAndDeletion() {
        var rows = DiffAlgorithm.diff("line1\nline2", "line1\nline3");

        assertEquals(2, DiffAlgorithm.countOld(rows));
        assertEquals(2, DiffAlgorithm.countNew(rows));
        assertTrue(rows.stream().anyMatch(r -> r.kind() == DiffAlgorithm.Kind.DELETE && r.text().equals("line2")));
        assertTrue(rows.stream().anyMatch(r -> r.kind() == DiffAlgorithm.Kind.ADD && r.text().equals("line3")));
    }

    @Test
    void identicalTextsProduceOnlyContext() {
        var rows = DiffAlgorithm.diff("a\nb\nc", "a\nb\nc");

        assertTrue(rows.stream().allMatch(r -> r.kind() == DiffAlgorithm.Kind.CONTEXT));
        assertEquals(3, rows.size());
    }

    @Test
    void emptyOldTextMarksAllAsAdded() {
        var rows = DiffAlgorithm.diff("", "new\nlines\n");

        assertTrue(rows.stream().allMatch(r -> r.kind() == DiffAlgorithm.Kind.ADD));
    }

    @Test
    void emptyNewTextMarksAllAsDeleted() {
        var rows = DiffAlgorithm.diff("old\nlines\n", "");

        assertTrue(rows.stream().allMatch(r -> r.kind() == DiffAlgorithm.Kind.DELETE));
    }

    @Test
    void hunksGroupNearbyChangesWithContext() {
        var rows = DiffAlgorithm.diff("a\nb\nc\nd\ne\nf\n", "a\nx\nd\ny\nf\n");
        var hunks = DiffAlgorithm.hunks(rows, 1);

        assertFalse(hunks.isEmpty());
        for (var hunk : hunks) {
            assertFalse(hunk.rows().isEmpty());
        }
    }
}
