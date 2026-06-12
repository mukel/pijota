package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranscendenceOrchestratorTest {

    @Test
    void generateBranchProducesSlugFromGoal() {
        String branch = TranscendenceOrchestrator.generateBranch("Add dark mode toggle");
        assertTrue(branch.startsWith("feat/add-dark-mode-toggle-"));
        assertTrue(branch.matches("feat/add-dark-mode-toggle-\\d{6}"));
        assertTrue(branch.endsWith(branch.substring(branch.length() - 6)));
    }

    @Test
    void generateBranchRemovesSpecialCharacters() {
        String branch = TranscendenceOrchestrator.generateBranch("Fix bug #42: login!");
        assertTrue(branch.startsWith("feat/fix-bug-42-login-"));
    }

    @Test
    void generateBranchTruncatesLongGoals() {
        String goal = "Implement a comprehensive refactoring of the authentication subsystem";
        String branch = TranscendenceOrchestrator.generateBranch(goal);
        assertEquals("feat/implement-a-comprehensive-refa", branch.substring(0, branch.length() - 7));
        assertFalse(branch.endsWith("-"));
        assertTrue(branch.matches("feat/.+-\\d{6}"));
    }

    @Test
    void generateBranchHandlesBlankGoal() {
        String branch = TranscendenceOrchestrator.generateBranch("   ");
        assertTrue(branch.startsWith("feat/update-"));
        assertEquals("feat/update", branch.substring(0, 11));
    }

    @Test
    void generateBranchCollapsesMultipleHyphens() {
        String branch = TranscendenceOrchestrator.generateBranch("Add   ---   feature");
        assertTrue(branch.startsWith("feat/add-feature-"));
        assertFalse(branch.contains("--"));
        assertFalse(branch.contains("   "));
    }

    @Test
    void generateBranchTimestampIsNumeric() {
        String branch = TranscendenceOrchestrator.generateBranch("test");
        String timestamp = branch.substring(branch.lastIndexOf('-') + 1);
        assertEquals(6, timestamp.length());
        assertTrue(timestamp.matches("\\d{6}"));
    }

    @Test
    void stripWrappingQuotesRemovesDoubleQuotes() {
        assertEquals("hello", TranscendenceOrchestrator.stripWrappingQuotes("\"hello\""));
    }

    @Test
    void stripWrappingQuotesRemovesSingleQuotes() {
        assertEquals("world", TranscendenceOrchestrator.stripWrappingQuotes("'world'"));
    }

    @Test
    void stripWrappingQuotesKeepsUnquotedStrings() {
        assertEquals("plain", TranscendenceOrchestrator.stripWrappingQuotes("plain"));
    }

    @Test
    void stripWrappingQuotesKeepsMismatchedQuotes() {
        assertEquals("\"partial", TranscendenceOrchestrator.stripWrappingQuotes("\"partial"));
        assertEquals("partial'", TranscendenceOrchestrator.stripWrappingQuotes("partial'"));
    }

    @Test
    void stripWrappingQuotesHandlesNull() {
        assertNull(TranscendenceOrchestrator.stripWrappingQuotes(null));
    }

    @Test
    void stripWrappingQuotesHandlesShortStrings() {
        assertEquals("a", TranscendenceOrchestrator.stripWrappingQuotes("a"));
        assertEquals("\"", TranscendenceOrchestrator.stripWrappingQuotes("\""));
    }

    @Test
    void stripWrappingQuotesHandlesEmptyQuoted() {
        assertEquals("", TranscendenceOrchestrator.stripWrappingQuotes("\"\""));
        assertEquals("", TranscendenceOrchestrator.stripWrappingQuotes("''"));
    }

    @Test
    void parseTranscendArgsGoalOnly() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("add dark mode");
        assertEquals("add dark mode", args.goal());
        assertFalse(args.testMode());
        assertFalse(args.rollback());
        assertNull(args.worktreePath());
        assertNull(args.mergeBranch());
    }

    @Test
    void parseTranscendArgsTestFlag() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("--test");
        assertTrue(args.testMode());
        assertNull(args.goal());
    }

    @Test
    void parseTranscendArgsRollbackFlag() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("--rollback");
        assertTrue(args.rollback());
    }

    @Test
    void parseTranscendArgsWorktreeFlag() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("--worktree /path/to/worktree");
        assertEquals("/path/to/worktree", args.worktreePath());
    }

    @Test
    void parseTranscendArgsMergeFlag() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("--merge feature-branch");
        assertEquals("feature-branch", args.mergeBranch());
    }

    @Test
    void parseTranscendArgsCombinedFlags() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("--test --worktree /tmp/wt fix bug");
        assertTrue(args.testMode());
        assertEquals("/tmp/wt", args.worktreePath());
        assertEquals("fix bug", args.goal());
    }

    @Test
    void parseTranscendArgsStripsQuotes() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("\"quoted goal\"");
        assertEquals("quoted goal", args.goal());
    }

    @Test
    void parseTranscendArgsEmpty() {
        var args = TranscendenceOrchestrator.parseTranscendArgs("");
        assertNull(args.goal());
        assertFalse(args.testMode());
        assertFalse(args.rollback());
    }

    @Test
    void stepConcatenatesIconAndLabel() {
        assertEquals("✓ done", TranscendenceOrchestrator.step("✓", "done"));
    }

    @Test
    void stepHandlesErrorIcon() {
        assertEquals("✕ failed", TranscendenceOrchestrator.step("✕", "failed"));
    }
}
