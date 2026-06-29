package ai.pijota.loop;

import ai.pijota.util.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link LoopRunner.Git} backed by the {@code git} CLI in a working directory — used for {@code --commit}. Same
 * {@link ProcessRunner} pattern as {@code WorktreePool}/{@code TaskTool}, so it's cross-platform and native-safe.
 */
final class LoopGit implements LoopRunner.Git {
    private final Path cwd;

    LoopGit(Path cwd) {
        this.cwd = cwd;
    }

    @Override
    public boolean isDirty() throws Exception {
        return !git(30, "status", "--porcelain").stdout().isBlank();
    }

    @Override
    public String commit(String message) throws Exception {
        git(60, "add", "-A");
        var r = git(120, "commit", "-m", message);
        if (r.exitCode() != 0) {
            if ((r.stdout() + r.stderr()).contains("nothing to commit"))
                return null;
            throw new RuntimeException("git commit failed: " + r.stderr().strip());
        }
        return git(10, "rev-parse", "--short", "HEAD").stdout().strip();
    }

    @Override
    public void rollback() throws Exception {
        git(60, "reset", "--hard", "HEAD");
        git(60, "clean", "-fd");
    }

    @Override
    public void push() throws Exception {
        var r = git(180, "push");
        if (r.exitCode() != 0)
            throw new RuntimeException("git push failed: " + r.stderr().strip());
    }

    private ProcessRunner.Result git(int timeoutSeconds, String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return ProcessRunner.command(cmd).workDir(cwd).timeout(timeoutSeconds, TimeUnit.SECONDS).run();
    }
}
