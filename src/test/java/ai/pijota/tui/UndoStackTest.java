package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UndoStackTest {

    // ─── beginsNewStep ───

    @Test
    void beginsNewStepOnEmptyStack() {
        var stack = new UndoStack();
        assertTrue(stack.beginsNewStep(UndoStack.Kind.TYPING));
        assertTrue(stack.beginsNewStep(UndoStack.Kind.DELETING));
        assertTrue(stack.beginsNewStep(UndoStack.Kind.OTHER));
    }

    @Test
    void beginsNewStepForOtherAlways() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("text", 0, 0), UndoStack.Kind.TYPING);
        assertTrue(stack.beginsNewStep(UndoStack.Kind.OTHER));
    }

    @Test
    void beginsNewStepForDifferentKind() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("text", 0, 0), UndoStack.Kind.TYPING);
        assertTrue(stack.beginsNewStep(UndoStack.Kind.DELETING));
    }

    @Test
    void doesNotBeginNewStepForSameKind() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("text", 0, 0), UndoStack.Kind.TYPING);
        assertFalse(stack.beginsNewStep(UndoStack.Kind.TYPING));
    }

    @Test
    void beginsNewStepAfterBreakRun() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("text", 0, 0), UndoStack.Kind.TYPING);
        stack.breakRun();
        assertTrue(stack.beginsNewStep(UndoStack.Kind.TYPING));
    }

    // ─── record ───

    @Test
    void recordPushesToUndoStack() {
        var stack = new UndoStack();
        var snap = new UndoStack.Snapshot("before", 0, 0);
        stack.record(snap, UndoStack.Kind.OTHER);
        var result = stack.undo(new UndoStack.Snapshot("after", 0, 1));
        assertTrue(result.isPresent());
        assertEquals(snap, result.get());
    }

    @Test
    void recordClearsRedoStack() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("s1", 0, 0), UndoStack.Kind.OTHER);
        stack.undo(new UndoStack.Snapshot("s2", 0, 0));
        stack.record(new UndoStack.Snapshot("s3", 0, 0), UndoStack.Kind.OTHER);
        assertTrue(stack.redo(new UndoStack.Snapshot("s4", 0, 0)).isEmpty());
    }

    @Test
    void recordCoalescesSameKindTyping() {
        var stack = new UndoStack();
        var first = new UndoStack.Snapshot("a", 0, 0);
        stack.record(first, UndoStack.Kind.TYPING);
        stack.record(new UndoStack.Snapshot("b", 0, 1), UndoStack.Kind.TYPING);
        var undo1 = stack.undo(new UndoStack.Snapshot("current", 0, 2));
        assertTrue(undo1.isPresent());
        assertEquals(first, undo1.get());
        assertTrue(stack.undo(new UndoStack.Snapshot("current", 0, 2)).isEmpty());
    }

    @Test
    void recordCoalescesSameKindDeleting() {
        var stack = new UndoStack();
        var first = new UndoStack.Snapshot("long text", 0, 5);
        stack.record(first, UndoStack.Kind.DELETING);
        stack.record(new UndoStack.Snapshot("short", 0, 4), UndoStack.Kind.DELETING);
        var undo1 = stack.undo(new UndoStack.Snapshot("current", 0, 0));
        assertTrue(undo1.isPresent());
        assertEquals(first, undo1.get());
        assertTrue(stack.undo(new UndoStack.Snapshot("current", 0, 0)).isEmpty());
    }

    @Test
    void recordDoesNotCoalesceOther() {
        var stack = new UndoStack();
        var first = new UndoStack.Snapshot("a", 0, 0);
        var second = new UndoStack.Snapshot("b", 0, 1);
        stack.record(first, UndoStack.Kind.OTHER);
        stack.record(second, UndoStack.Kind.OTHER);
        var undo1 = stack.undo(new UndoStack.Snapshot("c", 0, 2));
        var undo2 = stack.undo(new UndoStack.Snapshot("d", 0, 3));
        assertTrue(undo1.isPresent());
        assertTrue(undo2.isPresent());
        assertEquals(second, undo1.get());
        assertEquals(first, undo2.get());
    }

    @Test
    void recordOtherBreaksTypingRun() {
        var stack = new UndoStack();
        var first = new UndoStack.Snapshot("a", 0, 0);
        stack.record(first, UndoStack.Kind.TYPING);
        var second = new UndoStack.Snapshot("ab", 0, 2);
        stack.record(second, UndoStack.Kind.OTHER);
        var undo1 = stack.undo(new UndoStack.Snapshot("abc", 0, 3));
        var undo2 = stack.undo(new UndoStack.Snapshot("a", 0, 0));
        assertEquals(second, undo1.get());
        assertEquals(first, undo2.get());
    }

    // ─── breakRun ───

    @Test
    void breakRunStartsFreshStepForSameKind() {
        var stack = new UndoStack();
        var first = new UndoStack.Snapshot("a", 0, 0);
        stack.record(first, UndoStack.Kind.TYPING);
        stack.breakRun();
        var second = new UndoStack.Snapshot("ab", 0, 2);
        stack.record(second, UndoStack.Kind.TYPING);
        var undo1 = stack.undo(new UndoStack.Snapshot("abc", 0, 3));
        var undo2 = stack.undo(new UndoStack.Snapshot("a", 0, 0));
        assertEquals(second, undo1.get());
        assertEquals(first, undo2.get());
    }

    // ─── undo ───

    @Test
    void undoOnEmptyReturnsEmpty() {
        var stack = new UndoStack();
        assertTrue(stack.undo(new UndoStack.Snapshot("current", 0, 0)).isEmpty());
    }

    @Test
    void undoReturnsPreviousSnapshot() {
        var stack = new UndoStack();
        var before = new UndoStack.Snapshot("before", 0, 0);
        stack.record(before, UndoStack.Kind.OTHER);
        var current = new UndoStack.Snapshot("current", 0, 5);
        var result = stack.undo(current);
        assertTrue(result.isPresent());
        assertEquals(before, result.get());
    }

    @Test
    void undoPushesCurrentToRedo() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("before", 0, 0), UndoStack.Kind.OTHER);
        var current = new UndoStack.Snapshot("current", 0, 5);
        stack.undo(current);
        var redoResult = stack.redo(new UndoStack.Snapshot("before", 0, 0));
        assertTrue(redoResult.isPresent());
        assertEquals(current, redoResult.get());
    }

    @Test
    void undoBreaksRun() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("a", 0, 0), UndoStack.Kind.TYPING);
        stack.undo(new UndoStack.Snapshot("b", 0, 1));
        assertTrue(stack.beginsNewStep(UndoStack.Kind.TYPING));
    }

    @Test
    void multipleUndosInOrder() {
        var stack = new UndoStack();
        var s1 = new UndoStack.Snapshot("a", 0, 0);
        var s2 = new UndoStack.Snapshot("ab", 0, 2);
        var s3 = new UndoStack.Snapshot("abc", 0, 3);
        stack.record(s1, UndoStack.Kind.OTHER);
        stack.record(s2, UndoStack.Kind.OTHER);
        stack.record(s3, UndoStack.Kind.OTHER);
        assertEquals(s3, stack.undo(new UndoStack.Snapshot("abcd", 0, 4)).orElseThrow());
        assertEquals(s2, stack.undo(new UndoStack.Snapshot("abcd", 0, 4)).orElseThrow());
        assertEquals(s1, stack.undo(new UndoStack.Snapshot("abcd", 0, 4)).orElseThrow());
        assertTrue(stack.undo(new UndoStack.Snapshot("abcd", 0, 4)).isEmpty());
    }

    // ─── redo ───

    @Test
    void redoOnEmptyReturnsEmpty() {
        var stack = new UndoStack();
        assertTrue(stack.redo(new UndoStack.Snapshot("current", 0, 0)).isEmpty());
    }

    @Test
    void redoAfterUndoReturnsOriginal() {
        var stack = new UndoStack();
        var before = new UndoStack.Snapshot("before", 0, 0);
        stack.record(before, UndoStack.Kind.OTHER);
        var current = new UndoStack.Snapshot("current", 0, 5);
        stack.undo(current);
        var redoResult = stack.redo(before);
        assertTrue(redoResult.isPresent());
        assertEquals(current, redoResult.get());
    }

    @Test
    void redoPushesCurrentToUndo() {
        var stack = new UndoStack();
        var s1 = new UndoStack.Snapshot("a", 0, 0);
        var s2 = new UndoStack.Snapshot("b", 0, 1);
        stack.record(s1, UndoStack.Kind.OTHER);
        stack.undo(s2);
        stack.redo(s1);
        var undoResult = stack.undo(s2);
        assertTrue(undoResult.isPresent());
        assertEquals(s1, undoResult.get());
    }

    @Test
    void redoBreaksRun() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("a", 0, 0), UndoStack.Kind.TYPING);
        stack.undo(new UndoStack.Snapshot("b", 0, 1));
        stack.redo(new UndoStack.Snapshot("a", 0, 0));
        assertTrue(stack.beginsNewStep(UndoStack.Kind.TYPING));
    }

    @Test
    void multipleRedosAfterMultipleUndos() {
        var stack = new UndoStack();
        var s1 = new UndoStack.Snapshot("a", 0, 0);
        var s2 = new UndoStack.Snapshot("ab", 0, 2);
        var s3 = new UndoStack.Snapshot("abc", 0, 3);
        stack.record(s1, UndoStack.Kind.OTHER);
        stack.record(s2, UndoStack.Kind.OTHER);
        stack.record(s3, UndoStack.Kind.OTHER);
        var cur = new UndoStack.Snapshot("abcd", 0, 4);
        stack.undo(cur);
        stack.undo(s3);
        stack.undo(s2);
        assertEquals(s2, stack.redo(s1).orElseThrow());
        assertEquals(s3, stack.redo(s2).orElseThrow());
        assertEquals(cur, stack.redo(s3).orElseThrow());
        assertTrue(stack.redo(cur).isEmpty());
    }

    // ─── clear ───

    @Test
    void clearEmptiesUndoAndRedo() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("a", 0, 0), UndoStack.Kind.OTHER);
        stack.undo(new UndoStack.Snapshot("b", 0, 1));
        stack.clear();
        assertTrue(stack.undo(new UndoStack.Snapshot("c", 0, 0)).isEmpty());
        assertTrue(stack.redo(new UndoStack.Snapshot("c", 0, 0)).isEmpty());
    }

    @Test
    void clearResetsRunKind() {
        var stack = new UndoStack();
        stack.record(new UndoStack.Snapshot("a", 0, 0), UndoStack.Kind.TYPING);
        stack.clear();
        assertTrue(stack.beginsNewStep(UndoStack.Kind.TYPING));
    }

    // ─── MAX boundary ───

    @Test
    void maxEntriesEvictsOldestUndo() {
        var stack = new UndoStack();
        for (int i = 0; i < 205; i++) {
            stack.record(new UndoStack.Snapshot("s" + i, 0, i), UndoStack.Kind.OTHER);
        }
        int count = 0;
        var current = new UndoStack.Snapshot("current", 0, 0);
        while (stack.undo(current).isPresent()) {
            count++;
        }
        assertEquals(200, count);
    }

    // ─── full undo/redo cycle ───

    @Test
    void fullUndoRedoCycle() {
        var stack = new UndoStack();
        var s0 = new UndoStack.Snapshot("", 0, 0);
        var s1 = new UndoStack.Snapshot("hello", 0, 5);
        var s2 = new UndoStack.Snapshot("hello world", 0, 11);
        stack.record(s0, UndoStack.Kind.OTHER);
        stack.record(s1, UndoStack.Kind.OTHER);
        stack.record(s2, UndoStack.Kind.OTHER);
        var current = new UndoStack.Snapshot("hello world!", 0, 12);
        assertEquals(s2, stack.undo(current).orElseThrow());
        assertEquals(s1, stack.undo(s2).orElseThrow());
        assertEquals(s0, stack.undo(s1).orElseThrow());
        assertTrue(stack.undo(s0).isEmpty());
        assertEquals(s1, stack.redo(s0).orElseThrow());
        assertEquals(s2, stack.redo(s1).orElseThrow());
        assertEquals(current, stack.redo(s2).orElseThrow());
    }

    @Test
    void newRecordAfterUndoClearsRedoHistory() {
        var stack = new UndoStack();
        var s1 = new UndoStack.Snapshot("a", 0, 0);
        var s2 = new UndoStack.Snapshot("ab", 0, 2);
        stack.record(s1, UndoStack.Kind.OTHER);
        stack.record(s2, UndoStack.Kind.OTHER);
        stack.undo(new UndoStack.Snapshot("abc", 0, 3));
        stack.record(new UndoStack.Snapshot("new", 0, 0), UndoStack.Kind.OTHER);
        assertTrue(stack.redo(new UndoStack.Snapshot("x", 0, 0)).isEmpty());
    }

    @Test
    void coalescedTypingThenUndoThenRedo() {
        var stack = new UndoStack();
        var s0 = new UndoStack.Snapshot("", 0, 0);
        stack.record(s0, UndoStack.Kind.TYPING);
        stack.record(null, UndoStack.Kind.TYPING);
        stack.record(null, UndoStack.Kind.TYPING);
        var current = new UndoStack.Snapshot("abc", 0, 3);
        var undoResult = stack.undo(current);
        assertEquals(s0, undoResult.orElseThrow());
        var redoResult = stack.redo(s0);
        assertEquals(current, redoResult.orElseThrow());
    }
}
