package org.pijota.worktree;

import org.pijota.util.Executables;
import org.pijota.util.ProcessRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorktreePoolTest {
    @TempDir
    Path repo;
    @TempDir
    Path origin;
    @TempDir
    Path home;

    private String savedHome;

    @BeforeEach
    void setup() throws Exception {
        assumeTrue(Executables.isAvailable("git"), "needs git");
        savedHome = System.setProperty("pijota.home", home.toString());
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("a.txt"), "hello");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "init");
        // a local bare origin so default-ref resolution + prune's merged check have a remote to work against
        git(origin, "init", "-q", "--bare");
        git(repo, "remote", "add", "origin", origin.toString());
        git(repo, "push", "-q", "-u", "origin", "main");
        git(repo, "remote", "set-head", "origin", "main");
    }

    @AfterEach
    void restore() {
        if (savedHome != null)
            System.setProperty("pijota.home", savedHome);
        else
            System.clearProperty("pijota.home");
    }

    private WorktreePool pool() throws Exception {
        return WorktreePool.open(repo);
    }

    @Test
    void acquireCreatesADetachedWorktree() throws Exception {
        Path wt = pool().acquire(false);
        assertTrue(Files.isDirectory(wt), "worktree dir exists");
        assertEquals("true", git(wt, "rev-parse", "--is-inside-work-tree").strip());
        assertEquals("HEAD", git(wt, "rev-parse", "--abbrev-ref", "HEAD").strip(), "detached HEAD");
    }

    @Test
    void secondAcquireGivesADistinctWorktreeWhileFirstIsInUse() throws Exception {
        var p = pool();
        Path a = p.acquire(false); // owner = this jvm (alive) → in-use
        Path b = p.acquire(false); // can't reuse the in-use one → new dir
        assertNotEquals(a, b);
    }

    @Test
    void releaseThenAcquireReusesTheSameWorktree() throws Exception {
        var p = pool();
        Path a = p.acquire(false);
        p.release(a);
        Path b = p.acquire(false);
        assertEquals(a, b, "an idle worktree is reused, not re-created");
    }

    @Test
    void statusReportsDirtyForAnIdleModifiedWorktree() throws Exception {
        var p = pool();
        Path wt = p.acquire(false);
        p.release(wt); // idle + clean
        Files.writeString(wt.resolve("scratch.txt"), "uncommitted");
        var states = p.list().stream().map(s -> s.state()).toList();
        assertTrue(states.contains(WorktreePool.State.DIRTY), states.toString());
    }

    @Test
    void pruneRemovesAnIdleCleanMergedWorktree() throws Exception {
        var p = pool();
        Path wt = p.acquire(false);
        p.release(wt); // HEAD == origin/main → merged, idle, clean

        var dry = p.prune(false);
        assertNull(dry.skippedReason(), "origin is reachable");
        assertTrue(dry.candidates().contains(wt.toAbsolutePath().normalize()), dry.candidates().toString());
        assertFalse(dry.executed());
        assertTrue(Files.isDirectory(wt), "dry-run doesn't remove");

        var done = p.prune(true);
        assertTrue(done.executed());
        assertFalse(Files.exists(wt), "executed prune removes the worktree dir");
        assertTrue(p.list().isEmpty());
    }

    @Test
    void pruneSkipsADirtyWorktree() throws Exception {
        var p = pool();
        Path wt = p.acquire(false);
        p.release(wt);
        Files.writeString(wt.resolve("scratch.txt"), "uncommitted");
        var result = p.prune(true);
        assertTrue(result.candidates().isEmpty(), "dirty worktree is not pruned");
        assertTrue(Files.isDirectory(wt));
    }

    @Test
    void destroyRefusesDirtyWithoutFlagButRemovesWithIt() throws Exception {
        var p = pool();
        Path wt = p.acquire(false);
        p.release(wt);
        Files.writeString(wt.resolve("scratch.txt"), "uncommitted");

        var refused = p.destroy(wt, false, false, true);
        assertFalse(refused.removed());
        assertNotNull(refused.error());
        assertTrue(Files.isDirectory(wt));

        var forced = p.destroy(wt, true, false, true);
        assertTrue(forced.removed());
        assertFalse(Files.exists(wt));
    }

    @Test
    void poolJsonPersistsAcrossReopen() throws Exception {
        Path wt = pool().acquire(false);
        assertTrue(Files.exists(pool().poolRoot().resolve("pool.json")));
        // a freshly-opened pool sees the same worktree
        var listed = pool().list().stream().map(s -> s.path()).toList();
        assertTrue(listed.contains(wt.toAbsolutePath().normalize()), listed.toString());
    }

    @Test
    void pruneLeavesWorktreesUntouchedWhenOriginIsAbsent() throws Exception {
        // a repo with no remote → prune must do nothing (never risk removing unmerged work)
        git(repo, "remote", "remove", "origin");
        var p = pool();
        Path wt = p.acquire(false);
        p.release(wt);
        var result = p.prune(true);
        assertNotNull(result.skippedReason(), "no origin → skipped");
        assertTrue(result.candidates().isEmpty());
        assertTrue(Files.isDirectory(wt), "worktree survives");
    }

    /**
     * The pool forgot a vanished worktree from its own registry but never told git, so git kept the registration and
     * its metadata forever -- "prunable" in git's own words. They piled up in the user's repository and were invisible
     * to `pijota worktree prune`, whose candidates come from the registry after those entries are already gone.
     */
    @Test
    void aVanishedWorktreeIsDroppedFromGitsRegistryToo() throws Exception {
        var pool = pool();
        Path wt = pool.acquire(false);
        assertTrue(git(repo, "worktree", "list").contains(wt.toString()), "precondition: git knows the worktree");

        deleteRecursively(wt); // behind git's back, the way a crash or a manual rm leaves it

        pool.list(); // the self-heal

        String listed = git(repo, "worktree", "list");
        assertFalse(listed.contains(wt.toString()),
                                        "git still holds a registration for a directory that is gone:\n" + listed);
        assertFalse(listed.contains("prunable"), listed);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {}
            });
        }
    }

    private static String git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.command(cmd).workDir(dir).run();
        return r.stdout();
    }
}
