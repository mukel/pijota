package ai.pijota.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure arg-parsing tests for {@code pijota loop} (no bootstrap, no git). */
class LoopCommandTest {

    @Test
    void parsesLoopFlagsAndObjective() {
        var cfg = LoopCommand.parse(new String[] {"--max-iterations", "5", "--max-tokens", "100000",
                "tidy the README"});
        assertEquals("tidy the README", cfg.objective());
        assertEquals(5, cfg.options().maxIterations());
        assertEquals(100000, cfg.options().maxTokens());
        assertFalse(cfg.options().commit());
        assertFalse(cfg.worktree());
    }

    @Test
    void multiWordUnquotedObjectiveJoins() {
        var cfg = LoopCommand.parse(new String[] {"fix", "the", "bug"});
        assertEquals("fix the bug", cfg.objective());
    }

    @Test
    void commitFlag() {
        var cfg = LoopCommand.parse(new String[] {"--commit", "go"});
        assertTrue(cfg.options().commit());
        assertFalse(cfg.options().push());
    }

    @Test
    void pushImpliesCommit() {
        var cfg = LoopCommand.parse(new String[] {"--push", "go"});
        assertTrue(cfg.options().push());
        assertTrue(cfg.options().commit());
    }

    @Test
    void worktreeFlag() {
        var cfg = LoopCommand.parse(new String[] {"--worktree", "go"});
        assertTrue(cfg.worktree());
    }

    @Test
    void defaultsAreUnlimitedAndNonCommitting() {
        var cfg = LoopCommand.parse(new String[] {"just do it"});
        assertEquals(0, cfg.options().maxIterations());
        assertEquals(0, cfg.options().maxTokens());
        assertFalse(cfg.options().commit());
        assertFalse(cfg.worktree());
    }

    @Test
    void emptyWhenNoObjectiveGiven() {
        var cfg = LoopCommand.parse(new String[] {"--max-iterations", "2"});
        assertTrue(cfg.objective().isEmpty());
    }

    @Test
    void invalidNumericFlagIsIgnored() {
        var cfg = LoopCommand.parse(new String[] {"--max-iterations", "abc", "go"});
        // "abc" is consumed as the flag value (doesn't start with -), can't parse → 0; objective is the remaining token
        assertEquals(0, cfg.options().maxIterations());
        assertEquals("go", cfg.objective());
    }

    @Test
    void iterationTimeoutFlagParsedDefaultUnlimited() {
        assertEquals(0, LoopCommand.parse(new String[] {"go"}).iterationTimeoutSec());
        assertEquals(120, LoopCommand.parse(new String[] {"--iteration-timeout", "120", "go"}).iterationTimeoutSec());
    }

    @Test
    void sessionFlagsPassThrough() {
        var cfg = LoopCommand.parse(new String[] {"--thinking", "high", "go"});
        assertEquals("high", cfg.sessionArgs().thinkingLevel());
        assertEquals("go", cfg.objective());
    }
}
