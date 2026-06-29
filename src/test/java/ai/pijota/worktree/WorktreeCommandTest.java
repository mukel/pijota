package ai.pijota.worktree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeCommandTest {

    private static WorktreeCommand.Parsed parse(String... args) {
        var full = new String[args.length + 1];
        full[0] = "worktree";
        System.arraycopy(args, 0, full, 1, args.length);
        return WorktreeCommand.parse(full);
    }

    @Test
    void bareIsTheDefaultAction() {
        var p = parse();
        assertEquals(WorktreeCommand.Action.BARE, p.action());
        assertNull(p.error());
    }

    @Test
    void getWithLease() {
        var p = parse("get", "--lease");
        assertEquals(WorktreeCommand.Action.GET, p.action());
        assertTrue(p.lease());
    }

    @Test
    void statusAndListAlias() {
        assertEquals(WorktreeCommand.Action.STATUS, parse("status").action());
        assertEquals(WorktreeCommand.Action.STATUS, parse("list").action());
    }

    @Test
    void pruneWithYes() {
        var p = parse("prune", "--yes");
        assertEquals(WorktreeCommand.Action.PRUNE, p.action());
        assertTrue(p.yes());
    }

    @Test
    void destroyWithPathAndFlags() {
        var p = parse("destroy", "/tmp/wt-1", "--include-dirty", "--yes");
        assertEquals(WorktreeCommand.Action.DESTROY, p.action());
        assertEquals("/tmp/wt-1", p.path());
        assertTrue(p.includeDirty());
        assertTrue(p.yes());
        assertFalse(p.includeInUse());
    }

    @Test
    void returnDefaultsToNoPath() {
        var p = parse("return");
        assertEquals(WorktreeCommand.Action.RETURN, p.action());
        assertNull(p.path());
    }

    @Test
    void helpFlag() {
        assertTrue(parse("--help").help());
        assertTrue(parse("-h").help());
    }

    @Test
    void unknownSubcommandIsAnError() {
        var p = parse("bogus");
        assertNotNull(p.error());
        assertTrue(p.error().contains("unknown subcommand"), p.error());
    }

    @Test
    void unknownOptionIsAnError() {
        var p = parse("get", "--nope");
        assertNotNull(p.error());
        assertTrue(p.error().contains("unknown option"), p.error());
    }
}
