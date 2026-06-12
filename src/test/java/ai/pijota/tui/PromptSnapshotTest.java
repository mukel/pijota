package ai.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptSnapshotTest {

    @Test
    void basicConstructorStoresFields() {
        var snapshot = new PromptSnapshot(List.of("line1", "line2"), 0, 5, List.of());
        assertEquals(2, snapshot.lines().size());
        assertEquals(0, snapshot.cursorRow());
        assertEquals(5, snapshot.cursorCol());
        assertEquals(PromptSnapshot.PromptState.IDLE, snapshot.promptState());
    }

    @Test
    void withCwdReturnsNewSnapshot() {
        var original = new PromptSnapshot(List.of(">"), 0, 1, List.of());
        var updated = original.withCwd("/home/user");
        assertNotSame(original, updated);
        assertEquals("/home/user", updated.cwd());
        assertEquals(original.lines(), updated.lines());
    }

    @Test
    void withContextPctPreservesOtherFields() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of());
        var updated = original.withContextPct(75);
        assertEquals(original.lines(), updated.lines());
        assertEquals(75, updated.contextPct());
    }

    @Test
    void withStatusUpdatesOnlyStatus() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of()).withCwd("/tmp");
        var withStatus = original.withStatus("busy");
        assertEquals("busy", withStatus.status());
        assertEquals(original.lines(), withStatus.lines());
    }

    @Test
    void withWarningUpdatesOnlyWarning() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of());
        var withWarn = original.withWarning("disk full");
        assertEquals("disk full", withWarn.warning());
        assertNull(original.warning());
    }

    @Test
    void withPendingMessagesPreservesOtherFields() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of());
        var updated = original.withPendingMessages(List.of("msg1"));
        assertEquals(List.of("msg1"), updated.pendingMessages());
        assertEquals(original.lines(), updated.lines());
    }

    @Test
    void withExtensionLinesPreservesOtherFields() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of());
        var updated = original.withExtensionLines(List.of("ext1"));
        assertEquals(List.of("ext1"), updated.extensionLines());
    }

    @Test
    void withPromptStateChangesState() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of());
        var busy = original.withPromptState(PromptSnapshot.PromptState.BUSY);
        assertEquals(PromptSnapshot.PromptState.BUSY, busy.promptState());

        var err = original.withPromptState(PromptSnapshot.PromptState.ERROR);
        assertEquals(PromptSnapshot.PromptState.ERROR, err.promptState());
    }

    @Test
    void promptStateEnumHasAllValues() {
        assertNotNull(PromptSnapshot.PromptState.valueOf("IDLE"));
        assertNotNull(PromptSnapshot.PromptState.valueOf("BUSY"));
        assertNotNull(PromptSnapshot.PromptState.valueOf("ERROR"));
        assertNotNull(PromptSnapshot.PromptState.valueOf("CONTINUATION"));
    }

    @Test
    void withCompletionsConstructorDefaultsSelection() {
        var snapshot = new PromptSnapshot(List.of(">"), 0, 1, List.of(new CompletionItem("file1", "desc")));
        assertEquals(1, snapshot.completions().size());
        assertEquals(-1, snapshot.selectedCompletion());
    }

    @Test
    void withSuggestionUpdatesOnlySuggestion() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of());
        var updated = original.withSuggestion("xyz");
        assertEquals("xyz", updated.suggestion());
        assertNull(original.suggestion());
    }

    @Test
    void withHelpOverlayUpdatesOnlyHelpOverlay() {
        var original = new PromptSnapshot(List.of("x"), 0, 0, List.of());
        var updated = original.withHelpOverlay(List.of("line1", "line2"));
        assertEquals(List.of("line1", "line2"), updated.helpOverlay());
        assertTrue(original.helpOverlay().isEmpty());
    }
}
