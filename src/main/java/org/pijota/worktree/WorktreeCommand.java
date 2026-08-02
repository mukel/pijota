package org.pijota.worktree;

import org.pijota.util.Platform;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * The {@code pijota worktree} subcommand: a treehouse-style pool of reusable git worktrees for parallel sessions. Run
 * from a shell (no TUI, so no terminal/TTY conflict). Drives {@link WorktreePool}; {@code bare} opens an interactive
 * subshell in the acquired worktree and returns it to the pool on exit.
 */
public final class WorktreeCommand {

    private WorktreeCommand() {}

    enum Action {
        BARE, GET, STATUS, RETURN, PRUNE, DESTROY
    }

    record Parsed(Action action, boolean help, boolean lease, boolean yes, boolean includeDirty, boolean includeInUse,
                                    String path, String error) {
    }

    /** Pure arg parser (no IO); {@code args[0]} is "worktree". */
    static Parsed parse(String[] args) {
        Action action = Action.BARE;
        boolean help = false, lease = false, yes = false, includeDirty = false, includeInUse = false;
        String path = null;
        boolean actionSet = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> help = true;
                case "--lease" -> lease = true;
                case "--yes", "-y" -> yes = true;
                case "--include-dirty" -> includeDirty = true;
                case "--include-in-use" -> includeInUse = true;
                default -> {
                    if (a.startsWith("-"))
                        return error("unknown option: " + a);
                    if (!actionSet) {
                        var parsedAction = actionOf(a);
                        if (parsedAction == null)
                            return error("unknown subcommand: " + a);
                        action = parsedAction;
                        actionSet = true;
                    } else if (path == null) {
                        path = a;
                    } else {
                        return error("unexpected argument: " + a);
                    }
                }
            }
        }
        return new Parsed(action, help, lease, yes, includeDirty, includeInUse, path, null);
    }

    private static Action actionOf(String s) {
        return switch (s) {
            case "get" -> Action.GET;
            case "status", "list" -> Action.STATUS;
            case "return" -> Action.RETURN;
            case "prune" -> Action.PRUNE;
            case "destroy", "remove" -> Action.DESTROY;
            default -> null;
        };
    }

    private static Parsed error(String msg) {
        return new Parsed(Action.BARE, false, false, false, false, false, null, msg);
    }

    public static int run(String[] args) {
        var p = parse(args);
        if (p.help()) {
            printUsage(System.out);
            return 0;
        }
        if (p.error() != null) {
            System.err.println("pijota worktree: " + p.error());
            printUsage(System.err);
            return 2;
        }
        try {
            var pool = WorktreePool.open(Path.of(System.getProperty("user.dir")));
            return switch (p.action()) {
                case BARE -> bare(pool);
                case GET -> get(pool);
                case STATUS -> status(pool);
                case RETURN -> doReturn(pool, p.path());
                case PRUNE -> prune(pool, p.yes());
                case DESTROY -> destroy(pool, p);
            };
        } catch (IOException e) {
            System.err.println("pijota worktree: " + e.getMessage());
            return 1;
        }
    }

    private static int bare(WorktreePool pool) throws IOException {
        Path wt = pool.acquire(false);
        System.out.println("worktree: " + wt);
        System.out.println("(detached HEAD: commit + push or `git switch -c <branch>` before you exit, or the work is"
                                        + " discarded when this worktree is reused)");
        try {
            String shell = Platform.defaultShell();
            new ProcessBuilder(shell).inheritIO().directory(wt.toFile()).start().waitFor();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            pool.release(wt);
        }
    }

    private static int get(WorktreePool pool) throws IOException {
        Path wt = pool.acquire(true); // reserved until `pijota worktree return`
        System.out.println(wt); // path only on stdout, so it's scriptable: cd "$(pijota worktree get)"
        System.err.println("reserved · release with: pijota worktree return " + wt);
        return 0;
    }

    private static int status(WorktreePool pool) throws IOException {
        var entries = pool.list();
        if (entries.isEmpty()) {
            System.out.println("no worktrees in the pool (" + pool.poolRoot() + ")");
            return 0;
        }
        System.out.println("pool: " + pool.poolRoot());
        for (var s : entries) {
            System.out.printf(Locale.ROOT, "  %-8s %-10s %-8s %s%n", s.state().name().toLowerCase(Locale.ROOT),
                                            age(s.lastUsedMs()), s.head(), s.path());
        }
        return 0;
    }

    private static int doReturn(WorktreePool pool, String path) throws IOException {
        Path wt = path != null ? Path.of(path) : Path.of(System.getProperty("user.dir"));
        pool.release(wt);
        System.out.println("returned " + wt.toAbsolutePath().normalize());
        return 0;
    }

    private static int prune(WorktreePool pool, boolean yes) throws IOException {
        var result = pool.prune(yes);
        if (result.skippedReason() != null) {
            System.out.println("nothing pruned: " + result.skippedReason());
            return 0;
        }
        if (result.candidates().isEmpty()) {
            System.out.println("nothing to prune (no idle, clean, merged worktrees)");
            return 0;
        }
        System.out.println((result.executed() ? "pruned " : "would prune ") + result.candidates().size()
                                        + " worktree(s) [idle + clean + merged]:");
        for (Path wt : result.candidates())
            System.out.println("  " + wt);
        if (!result.executed())
            System.out.println("re-run with --yes to remove them");
        return 0;
    }

    private static int destroy(WorktreePool pool, Parsed p) throws IOException {
        if (p.path() == null) {
            System.err.println("pijota worktree: destroy needs a worktree path");
            return 2;
        }
        var result = pool.destroy(Path.of(p.path()), p.includeDirty(), p.includeInUse(), p.yes());
        if (result.error() != null) {
            System.err.println("pijota worktree: " + result.error());
            return 1;
        }
        System.out.println((result.removed() ? "destroyed " : "would destroy ") + Path.of(p.path()));
        if (!result.removed())
            System.out.println("re-run with --yes to remove it");
        return 0;
    }

    private static String age(long ms) {
        if (ms <= 0)
            return "-";
        long secs = Duration.between(Instant.ofEpochMilli(ms), Instant.now()).getSeconds();
        if (secs < 60)
            return secs + "s";
        if (secs < 3600)
            return (secs / 60) + "m";
        if (secs < 86400)
            return (secs / 3600) + "h";
        return (secs / 86400) + "d";
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                                        pijota worktree: a reusable pool of git worktrees for parallel sessions

                                        USAGE:
                                          pijota worktree                 acquire a worktree and open a shell in it
                                                                          (returned to the pool on exit)
                                          pijota worktree get [--lease]   acquire + print the path (reserved until return)
                                          pijota worktree status          list the pool (idle / in-use / leased / dirty)
                                          pijota worktree return [path]   release a worktree back to the pool
                                          pijota worktree prune [--yes]    remove stale worktrees (idle + clean + merged)
                                          pijota worktree destroy <path> [--yes] [--include-dirty] [--include-in-use]

                                        Worktrees are detached-HEAD and disposable: commit + push (or git switch -c) your
                                        work before leaving. Reuse/prune never touch a dirty or unmerged worktree.""");
    }
}
