package org.pijota.worktree;

import org.pijota.util.Executables;
import org.pijota.util.PijotaHome;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A reusable pool of detached git worktrees for running pijota sessions in parallel without colliding. Idle worktrees
 * are <em>reset and reused</em> (build artifacts stay warm) rather than re-created; {@link #prune} reclaims stale ones.
 * Inspired by <a href="https://github.com/kunchenguid/treehouse">treehouse</a>. All git runs through
 * {@link ProcessRunner}; the pool registry ({@code pool.json}) is mutated under a {@code pool.lock} file lock so
 * parallel {@code pijota worktree} invocations never hand out the same worktree.
 */
public final class WorktreePool {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(120);

    private final Path repoRoot; // the main working tree (git commands run here)
    private final Path poolRoot; // ~/.pijota/worktrees/<repo-id>/
    private final Path registryFile; // poolRoot/pool.json
    private final Path lockFile; // poolRoot/pool.lock

    private WorktreePool(Path repoRoot, Path poolRoot) {
        this.repoRoot = repoRoot;
        this.poolRoot = poolRoot;
        this.registryFile = poolRoot.resolve("pool.json");
        this.lockFile = poolRoot.resolve("pool.lock");
    }

    /** Opens the pool for the repo containing {@code cwd}. Throws if git is unavailable or {@code cwd} isn't a repo. */
    public static WorktreePool open(Path cwd) throws IOException {
        if (!Executables.isAvailable("git"))
            throw new IOException("git is not installed or not on PATH");
        // --git-common-dir resolves to the MAIN repo's .git even when invoked from inside a linked worktree, so the
        // pool is keyed on the repository, not the current worktree.
        var common = ProcessRunner.command("git", "rev-parse", "--path-format=absolute", "--git-common-dir")
                                        .workDir(cwd).timeout(GIT_TIMEOUT).run();
        if (!common.success())
            throw new IOException("not a git repository: " + cwd);
        Path commonDir = Path.of(common.stdout().strip()).toAbsolutePath().normalize();
        Path repoRoot = commonDir.getFileName() != null && commonDir.getFileName().toString().equals(".git")
                                        ? commonDir.getParent()
                                        : commonDir;
        String repoId = sanitize(repoRoot.getFileName().toString()) + "-"
                                        + Integer.toHexString(repoRoot.toString().hashCode());
        Path poolRoot = PijotaHome.resolve("worktrees", repoId);
        return new WorktreePool(repoRoot, poolRoot);
    }

    public Path poolRoot() {
        return poolRoot;
    }

    // ---- acquire / release ----

    /** Reuses an idle+clean worktree (reset to the latest default), or creates one. Marks it in-use (or leased). */
    public Path acquire(boolean lease) throws IOException {
        return withLock(() -> {
            var reg = load();
            reg.removeIf(e -> !Files.isDirectory(Path.of(e.path)));
            String ref = defaultRef();
            Entry reuse = reg.stream().filter(e -> !inUse(e)).filter(e -> !isDirty(Path.of(e.path))).findFirst()
                                            .orElse(null);
            Path wt;
            if (reuse != null) {
                wt = Path.of(reuse.path);
                reset(wt, ref);
            } else {
                wt = poolRoot.resolve("wt-" + nextIndex(reg));
                var add = git(repoRoot, "worktree", "add", "--detach", wt.toString(), ref);
                if (!add.success())
                    throw new IOException("git worktree add failed: " + add.stderr().strip());
                reg.add(new Entry(wt.toString(), now(), now(), false, null));
            }
            reg.replaceAll(e -> e.path.equals(wt.toString())
                                            ? new Entry(e.path, e.createdMs, now(), lease, lease ? null : pid())
                                            : e);
            save(reg);
            return wt;
        });
    }

    /** Resets a worktree to the latest default and returns it to the pool (clears owner + lease). */
    public void release(Path worktree) throws IOException {
        Path wt = worktree.toAbsolutePath().normalize();
        withLock(() -> {
            var reg = load();
            boolean known = reg.stream().anyMatch(e -> samePath(e.path, wt));
            if (!known)
                return null; // not a managed worktree, nothing to do
            reset(wt, defaultRef());
            reg.replaceAll(e -> samePath(e.path, wt) ? new Entry(e.path, e.createdMs, now(), false, null) : e);
            save(reg);
            return null;
        });
    }

    // ---- inspection ----

    public List<Status> list() throws IOException {
        return withLock(() -> {
            var reg = load();
            forgetVanished(reg);
            var out = new ArrayList<Status>();
            for (var e : reg) {
                Path wt = Path.of(e.path);
                State state = e.leased
                                                ? State.LEASED
                                                : ownerAlive(e.ownerPid)
                                                                                ? State.IN_USE
                                                                                : isDirty(wt)
                                                                                                                ? State.DIRTY
                                                                                                                : State.IDLE;
                out.add(new Status(wt, state, shortHead(wt), e.lastUsedMs));
            }
            out.sort(Comparator.comparing(s -> s.path().toString()));
            return out;
        });
    }

    // ---- prune (manual only) ----

    /**
     * Drops entries whose directory is gone, from this pool's registry and from git's.
     *
     * <p>
     * Only half of that used to happen: the pool forgot the worktree and git kept its registration and its
     * {@code .git/worktrees/<name>} metadata forever, which is what {@code git worktree list} calls "prunable". They
     * accumulated in the user's own repository -- eleven of them, on the machine this was found on -- and were
     * invisible to {@code pijota worktree prune}, whose candidates come from this registry after the entries have
     * already been dropped from it.
     *
     * <p>
     * Safe to do unprompted, unlike removing a worktree: the directory is already gone, so there is no work to lose.
     * That is why it happens here rather than behind {@code --yes}.
     */
    private void forgetVanished(List<Entry> reg) throws IOException {
        if (!reg.removeIf(e -> !Files.isDirectory(Path.of(e.path))))
            return;
        save(reg);
        git(repoRoot, "worktree", "prune");
    }

    /** Removes stale worktrees (idle + clean + merged into origin's default). Dry-run unless {@code execute}. */
    public PruneResult prune(boolean execute) throws IOException {
        return withLock(() -> {
            var reg = load();
            forgetVanished(reg);
            String originDefault = originDefaultRef();
            if (originDefault == null) // origin unreachable / no remote: never risk removing unmerged work
                return new PruneResult(List.of(), false, "no reachable origin default branch; nothing pruned");
            // best-effort refresh so "merged" is judged against the real remote tip
            git(repoRoot, "fetch", "origin", originDefault.substring("origin/".length()));
            var candidates = new ArrayList<Path>();
            for (var e : reg) {
                Path wt = Path.of(e.path);
                if (!inUse(e) && !isDirty(wt) && isMerged(wt, originDefault))
                    candidates.add(wt);
            }
            if (execute) {
                for (Path wt : candidates)
                    removeWorktree(wt);
                reg.removeIf(e -> candidates.contains(Path.of(e.path)));
                save(reg);
                git(repoRoot, "worktree", "prune");
            }
            return new PruneResult(List.copyOf(candidates), execute, null);
        });
    }

    /** Removes a single worktree; refuses dirty/in-use unless the matching {@code include*} flag is set. */
    public DestroyResult destroy(Path worktree, boolean includeDirty, boolean includeInUse, boolean execute)
                                    throws IOException {
        Path wt = worktree.toAbsolutePath().normalize();
        return withLock(() -> {
            var reg = load();
            var entry = reg.stream().filter(e -> samePath(e.path, wt)).findFirst().orElse(null);
            if (entry == null)
                return new DestroyResult(false, "not a managed worktree: " + wt);
            if (inUse(entry) && !includeInUse)
                return new DestroyResult(false, "worktree is in use (pass --include-in-use to force): " + wt);
            if (isDirty(wt) && !includeDirty)
                return new DestroyResult(false, "worktree has uncommitted changes (pass --include-dirty to force): "
                                                + wt);
            if (execute) {
                removeWorktree(wt);
                reg.removeIf(e -> samePath(e.path, wt));
                save(reg);
                git(repoRoot, "worktree", "prune");
            }
            return new DestroyResult(execute, null);
        });
    }

    // ---- git helpers ----

    private void reset(Path wt, String ref) throws IOException {
        git(wt, "reset", "--hard", ref);
        git(wt, "clean", "-fd"); // keep gitignored build artifacts warm (no -x)
    }

    private void removeWorktree(Path wt) throws IOException {
        git(repoRoot, "worktree", "remove", "--force", wt.toString());
        deleteRecursive(wt);
    }

    private boolean isDirty(Path wt) {
        try {
            return !gitOut(wt, "status", "--porcelain").isEmpty();
        } catch (IOException e) {
            return true; // can't tell → treat as dirty (never auto-remove)
        }
    }

    private boolean isMerged(Path wt, String originDefault) throws IOException {
        String head = gitOut(wt, "rev-parse", "HEAD");
        if (head.isEmpty())
            return false;
        return git(repoRoot, "merge-base", "--is-ancestor", head, originDefault).success();
    }

    /** The ref to check out: default branch tip, preferring whichever of local vs origin is further ahead. */
    private String defaultRef() throws IOException {
        String def = defaultBranchName();
        boolean hasLocal = revParseOk("refs/heads/" + def);
        boolean hasRemote = revParseOk("refs/remotes/origin/" + def);
        if (hasLocal && hasRemote)
            return commitCount("origin/" + def) >= commitCount(def) ? "origin/" + def : def;
        if (hasRemote)
            return "origin/" + def;
        if (hasLocal)
            return def;
        return "HEAD";
    }

    /** "origin/&lt;default&gt;" if it resolves, else null (origin unreachable / no remote). */
    private String originDefaultRef() throws IOException {
        String def = defaultBranchName();
        return revParseOk("refs/remotes/origin/" + def) ? "origin/" + def : null;
    }

    private String defaultBranchName() throws IOException {
        var sym = git(repoRoot, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        if (sym.success()) {
            String s = sym.stdout().strip();
            if (s.startsWith("origin/"))
                return s.substring("origin/".length());
        }
        for (String cand : List.of("main", "master"))
            if (revParseOk("refs/heads/" + cand) || revParseOk("refs/remotes/origin/" + cand))
                return cand;
        return "main";
    }

    private boolean revParseOk(String ref) throws IOException {
        return git(repoRoot, "rev-parse", "--verify", "--quiet", ref + "^{commit}").success();
    }

    private long commitCount(String ref) throws IOException {
        try {
            return Long.parseLong(gitOut(repoRoot, "rev-list", "--count", ref));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String shortHead(Path wt) {
        try {
            return gitOut(wt, "rev-parse", "--short", "HEAD");
        } catch (IOException e) {
            return "?";
        }
    }

    private ProcessRunner.Result git(Path workDir, String... args) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        boolean isFetch = args.length > 0 && args[0].equals("fetch");
        return ProcessRunner.command(cmd).workDir(workDir).timeout(isFetch ? FETCH_TIMEOUT : GIT_TIMEOUT).run();
    }

    private String gitOut(Path workDir, String... args) throws IOException {
        return git(workDir, args).stdout().strip();
    }

    // ---- registry (pool.json) ----

    private List<Entry> load() throws IOException {
        if (!Files.exists(registryFile))
            return new ArrayList<>();
        try {
            var reg = Util.MAPPER.readValue(Files.readString(registryFile), Registry.class);
            return reg.worktrees() != null ? new ArrayList<>(reg.worktrees()) : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>(); // corrupt registry → start fresh (worktrees still discoverable via git)
        }
    }

    private void save(List<Entry> entries) throws IOException {
        Files.createDirectories(poolRoot);
        Path tmp = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
        Files.writeString(tmp, Util.MAPPER.writerWithDefaultPrettyPrinter()
                                        .writeValueAsString(new Registry(List.copyOf(entries))));
        Util.replaceFile(tmp, registryFile);
    }

    private interface LockedBody<T> {
        T run() throws IOException;
    }

    private <T> T withLock(LockedBody<T> body) throws IOException {
        Files.createDirectories(poolRoot);
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                                        FileLock ignored = ch.lock()) {
            return body.run();
        }
    }

    private boolean inUse(Entry e) {
        return e.leased || ownerAlive(e.ownerPid);
    }

    private static boolean ownerAlive(Long pid) {
        return pid != null && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static long pid() {
        return ProcessHandle.current().pid();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static int nextIndex(List<Entry> reg) {
        int max = 0;
        for (var e : reg) {
            String name = Path.of(e.path).getFileName().toString();
            if (name.startsWith("wt-")) {
                try {
                    max = Math.max(max, Integer.parseInt(name.substring(3)));
                } catch (NumberFormatException ignored) {}
            }
        }
        return max + 1;
    }

    private static boolean samePath(String a, Path b) {
        return Path.of(a).toAbsolutePath().normalize().equals(b);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(Locale.ROOT);
    }

    private static void deleteRecursive(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // ---- types ----

    public enum State {
        IDLE, IN_USE, LEASED, DIRTY
    }

    public record Status(Path path, State state, String head, long lastUsedMs) {
    }

    public record PruneResult(List<Path> candidates, boolean executed, String skippedReason) {
    }

    public record DestroyResult(boolean removed, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Registry(List<Entry> worktrees) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(String path, long createdMs, long lastUsedMs, boolean leased, Long ownerPid) {
    }
}
