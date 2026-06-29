package ai.pijota.loop;

import ai.pijota.agent.TestSession;
import ai.pijota.testkit.FauxLlm;
import ai.pijota.util.Executables;
import ai.pijota.util.ProcessRunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static ai.pijota.loop.LoopTestSupport.opts;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Integration: the loop core driving the real {@code git} CLI ({@link LoopGit}) and a {@link TestSession}. */
class LoopIntegrationTest {

    @TempDir
    Path repo;

    @BeforeEach
    void setup() throws Exception {
        assumeTrue(Executables.isAvailable("git"), "needs git");
        git("init", "-q", "-b", "main");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "Test");
        Files.writeString(repo.resolve("seed.txt"), "seed");
        git("add", "-A");
        git("commit", "-q", "-m", "init");
    }

    @Test
    void commitModeCommitsOneCommitPerDirtyIteration() throws Exception {
        var git = new LoopGit(repo);
        var n = new AtomicInteger();
        // each iteration writes a new file → the tree is dirty → LoopGit commits it
        LoopRunner.Step step = p -> {
            int i = n.incrementAndGet();
            Files.writeString(repo.resolve("work-" + i + ".txt"), "change " + i);
            return "added work " + i;
        };
        var s = LoopRunner.run("build", opts(3, 0, true), step, () -> 0, () -> 0, git, () -> false, m -> {});
        assertEquals(3, s.iterations());
        assertEquals(3, s.commits());
        assertEquals(4, commitCount(), "3 loop commits on top of the init commit");
        assertTrue(lastCommitSubject().startsWith("pijota loop: iteration 3 — added work 3"));
    }

    @Test
    void cleanIterationStopsNaturallyInCommitMode() throws Exception {
        var git = new LoopGit(repo);
        LoopRunner.Step noChange = p -> "nothing to do"; // never touches the tree
        var s = LoopRunner.run("noop", opts(0, 0, true), noChange, () -> 0, () -> 0, git, () -> false, m -> {});
        assertEquals(1, s.iterations());
        assertEquals(0, s.commits());
        assertEquals("done (no changes)", s.stopReason());
        assertEquals(1, commitCount(), "only the init commit remains");
    }

    @Test
    void failingIterationRollsBackUncommittedWork() throws Exception {
        var git = new LoopGit(repo);
        // Iteration writes a file then fails → LoopGit.rollback() must reset+clean it away.
        LoopRunner.Step dirtyThenFail = p -> {
            Files.writeString(repo.resolve("partial.txt"), "half-done");
            throw new RuntimeException("crashed mid-iteration");
        };
        var s = LoopRunner.run("x", opts(0, 0, true), dirtyThenFail, () -> 0, () -> 0, git, () -> false, m -> {});
        assertEquals("too many consecutive failures", s.stopReason());
        assertFalse(Files.exists(repo.resolve("partial.txt")), "rollback removed the partial file");
        assertEquals(1, commitCount());
    }

    @Test
    void fauxLlmUsageDrivesMaxTokensStop() throws Exception {
        var ts = TestSession.in(repo).noSession().build();
        for (int i = 0; i < 5; i++)
            ts.llm().enqueue(FauxLlm.Response.contentWithUsage("working"));
        var used = new AtomicLong();
        // Run a real session prompt each iteration; simulate accumulating usage via the FauxLlm token counters.
        LoopRunner.Step step = p -> {
            ts.session().prompt(p).get(5, SECONDS);
            used.addAndGet(50);
            ts.llm().tokens(used.get(), 0);
            return "ok";
        };
        var s = LoopRunner.run("obj", opts(0, 120, false), step,
                                        () -> ts.llm().totalInputTokens() + ts.llm().totalOutputTokens(), () -> 0,
                                        new LoopRunner.NoGit(), () -> false, m -> {});
        assertEquals(3, s.iterations()); // 0,50,100 < 120 run; 150 ≥ 120 stops
        assertTrue(s.stopReason().startsWith("max-tokens"), s.stopReason());
    }

    @Test
    void commitsLandOnAPijotaLoopBranch() throws Exception {
        git("switch", "-q", "-c", "pijota/loop-12345");
        var git = new LoopGit(repo);
        LoopRunner.Step step = p -> {
            Files.writeString(repo.resolve("on-branch.txt"), "x");
            return "work";
        };
        var s = LoopRunner.run("b", opts(1, 0, true), step, () -> 0, () -> 0, git, () -> false, m -> {});
        assertEquals(1, s.commits());
        assertEquals("pijota/loop-12345", git("rev-parse", "--abbrev-ref", "HEAD").strip());
    }

    private long commitCount() throws Exception {
        return Long.parseLong(git("rev-list", "--count", "HEAD").strip());
    }

    private String lastCommitSubject() throws Exception {
        return git("log", "-1", "--pretty=%s").strip();
    }

    private String git(String... args) throws Exception {
        var cmd = new ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.command(cmd).workDir(repo).run();
        return r.stdout();
    }
}
